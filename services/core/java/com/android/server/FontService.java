/*
 * Copyright (C) 2018 The Dirty Unicorns Project
 *               2021 AOSP-Krypton Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server;

import static android.os.FileUtils.S_IRGRP;
import static android.os.FileUtils.S_IROTH;
import static android.os.FileUtils.S_IRWXU;
import static android.os.FileUtils.S_IXOTH;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.ContentResolver;
import android.content.Context;
import android.content.FontInfo;
import android.content.IFontService;
import android.content.IFontServiceCallback;
import android.content.om.IOverlayManager;
import android.os.Binder;
import android.os.Environment;
import android.os.FileUtils;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.os.RemoteException;
import android.os.SELinux;
import android.os.ServiceManager;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.function.Consumer;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.stream.StreamResult;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerConfigurationException;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpressionException;
import javax.xml.xpath.XPathFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.xml.sax.SAXException;

/**
 * Service that manages user applied fonts.
 * @hide
 */
public class FontService extends IFontService.Stub {
    private static final String TAG = "FontService";
    private static final boolean DEBUG = false;

    // /data/system/theme
    private static final File sThemeDir = new File(Environment.getDataSystemDirectory(), "theme");
    // Directory to place current font.ttf and fonts.xml file
    private static final File sCurrentFontDir = new File(sThemeDir, "fonts");
    // Directory to place all of the selected fonts
    private static final File sSavedFontsDir = new File(sThemeDir, "saved_fonts");

    // Font configuration file name
    private static final String FONTS_XML = "fonts.xml";
    // Template xml file for font configuration
    private static final File sFontConfigXml = new File(Environment.getRootDirectory(),
        "etc/custom_font_config.xml");

    // Handler messages
    private static final int MESSAGE_INITIALIZE_FONT_MAP = 1;
    private static final int MESSAGE_FONT_CHANGED = 2;
    private static final int MESSAGE_ADD_FONTS = 3;
    private static final int MESSAGE_REMOVE_FONTS = 4;
    private static final int MESSAGE_REFRESH_FONTS = 5;
    private static final int MESSAGE_ADD_CALLBACK = 6;
    private static final int MESSAGE_REMOVE_CALLBACK = 7;

    private final Context mContext;
    private final FontHandler mFontHandler;
    private final List<WeakReference<IFontServiceCallback>> mCallbacks;
    private final HashMap<String, FontInfo> mFontMap;
    private final FontInfo mFontInfo;

    private final XPath mXPath;
    private DocumentBuilder mDocBuilder;
    private Transformer mTransformer;

    public static class Lifecycle extends SystemService {
        FontService mService;

        public Lifecycle(Context context) {
            super(context);
        }

        @Override
        public void onStart() {
            mService = new FontService(getContext());
            publishBinderService("dufont", mService);
        }

        @Override
        public void onBootPhase(int phase) {
            if (phase == PHASE_SYSTEM_SERVICES_READY) {
                final String cryptState = SystemProperties.get("vold.decrypt");
                // wait until decrypted if we use FDE or just go one if not (cryptState will be empty then)
                if (isEmpty(cryptState) || cryptState.equals("trigger_restart_framework")) {
                    if (sThemeDir.isDirectory()) {
                        restoreconThemeDir();
                    } else {
                        Log.e(TAG, "Directory " + sThemeDir.getAbsolutePath() + " does not exist!");
                    }
                    mService.sendInitializeFontMapMessage();
                }
            } else if (phase == PHASE_THIRD_PARTY_APPS_CAN_START) {
                mService.sendRefreshFontsMessage();
            }
        }
    }

    private class FontHandler extends Handler {
        public FontHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            logD("msg.what = " + msg.what);
            switch (msg.what) {
                case MESSAGE_INITIALIZE_FONT_MAP:
                    initializeFontMap();
                    break;
                case MESSAGE_FONT_CHANGED:
                    applyFontInternal((FontInfo) msg.obj);
                    break;
                case MESSAGE_ADD_FONTS:
                    addFontsInternal((Map<String, ParcelFileDescriptor>) msg.obj);
                    break;
                case MESSAGE_REMOVE_FONTS:
                    removeFontsInternal((List<String>) msg.obj);
                    break;
                case MESSAGE_REFRESH_FONTS:
                    refreshFonts();
                    break;
                case MESSAGE_ADD_CALLBACK:
                    addCallback((IFontServiceCallback) msg.obj);
                    break;
                case MESSAGE_REMOVE_CALLBACK:
                    removeCallback((IFontServiceCallback) msg.obj);
                    break;
                default:
                    Log.w(TAG, "Unknown message " + msg.what);
            }
        }
    }

    public FontService(Context context) {
        mContext = context;
        final HandlerThread thread = new HandlerThread(TAG, Process.THREAD_PRIORITY_BACKGROUND);
        thread.start();
        mFontHandler = new FontHandler(thread.getLooper());
        mCallbacks = new ArrayList<>();
        mFontMap = new HashMap<>();
        mFontInfo = new FontInfo();

        try {
            mDocBuilder = DocumentBuilderFactory.newInstance().newDocumentBuilder();
            mTransformer = TransformerFactory.newInstance().newTransformer();
            mTransformer.setOutputProperty(OutputKeys.INDENT, "yes");
            mTransformer.setOutputProperty(OutputKeys.METHOD, "xml");
            mTransformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "4");
        } catch (ParserConfigurationException|TransformerConfigurationException e) {
            Log.e(TAG, "Error on instantiation", e);
        }
        mXPath = XPathFactory.newInstance().newXPath();
        updateCurrentInfoFromSettings();
    }

    @Override
    public void applyFont(@Nullable FontInfo info) {
        enforcePermissions();
        mFontHandler.sendMessage(mFontHandler.obtainMessage(
            MESSAGE_FONT_CHANGED, info));
    }

    @Override
    public FontInfo getFontInfo() {
        enforcePermissions();
        return mFontInfo.clone();
    }

    @Override
    public void addFonts(Map<String, ParcelFileDescriptor> map) {
        enforcePermissions();
        mFontHandler.sendMessage(mFontHandler.obtainMessage(
            MESSAGE_ADD_FONTS, map));
    }

    @Override
    public void removeFonts(List<String> list) {
        enforcePermissions();
        mFontHandler.sendMessage(mFontHandler.obtainMessage(
            MESSAGE_REMOVE_FONTS, list));
    }

    @Override
    public Map<String, FontInfo> getAllFonts() {
        enforcePermissions();
        return new HashMap<String, FontInfo>(mFontMap);
    }

    @Override
    public void registerCallback(@NonNull IFontServiceCallback callback) throws IllegalArgumentException {
        if (callback == null) {
            throw new IllegalArgumentException("Attempt to register a null callback");
        }
        mFontHandler.sendMessage(mFontHandler.obtainMessage(MESSAGE_ADD_CALLBACK, callback));
    }

    @Override
    public void unregisterCallback(@NonNull IFontServiceCallback callback) throws IllegalArgumentException {
        if (callback == null) {
            throw new IllegalArgumentException("Attempt to unregister a null callback");
        }
        mFontHandler.sendMessage(mFontHandler.obtainMessage(MESSAGE_REMOVE_CALLBACK, callback));
    }

    private void sendInitializeFontMapMessage() {
        mFontHandler.sendMessage(mFontHandler.obtainMessage(
                MESSAGE_INITIALIZE_FONT_MAP));
    }

    private void initializeFontMap() {
        logD("initializeFontMap");
        final String[] fonts = getAvailableFontsFromSettings();
        logD("fonts = " + fonts);

        if (fonts != null) {
            for (String font: fonts) {
                final File fontDir = new File(sSavedFontsDir, font);
                // Check if the directory exists first.
                if (!fontDir.isDirectory()) {
                    removeFontFromSettings(font);
                    return;
                }

                // Check if the xml file exists.
                final File fontXML = new File(fontDir, FONTS_XML);
                if (!fontXML.isFile()) {
                    removeFontFromSettings(font);
                    return;
                }

                // Check if the ttf file exists.
                final File fontFile = new File(fontDir, appendExtension(font));
                if (!fontFile.isFile()) {
                    removeFontFromSettings(font);
                    return;
                }

                // create FontInfo and add to map
                mFontMap.put(font, new FontInfo(font, fontFile.getAbsolutePath()));
            }
        }
        logD("Font list initialized, list = " + mFontMap);

        // Copy necessary files to fonts dir in case they were deleted
        if (!mFontInfo.equals(FontInfo.getDefaultFontInfo())) {
            copyFont(mFontInfo);
        }
    }

    private void sendRefreshFontsMessage() {
        mFontHandler.sendMessage(mFontHandler.obtainMessage(
                MESSAGE_REFRESH_FONTS));
    }

    private void refreshFonts() {
        // Set permissions on font files and config xml
        if (sCurrentFontDir.isDirectory()) {
            setPermissionsRecursive(sCurrentFontDir,
                S_IRWXU | S_IRGRP | S_IROTH | S_IXOTH,
                S_IRWXU | S_IRGRP | S_IROTH | S_IXOTH);
            restoreconThemeDir();
        } else {
            makeDir(sCurrentFontDir);
        }

        // Reload resources for core packages
        try {
            IOverlayManager om = IOverlayManager.Stub.asInterface(
                ServiceManager.getService(Context.OVERLAY_SERVICE));
            om.reloadAssets("android", UserHandle.USER_CURRENT);
            om.reloadAssets("com.android.systemui", UserHandle.USER_CURRENT);
        } catch (RemoteException e) {
            Log.e(TAG, "Unable to reload resources", e);
        }
    }

    private void addCallback(IFontServiceCallback callback) {
        logD("addCallback, callback = " + callback);
        boolean containsCallback = false;
        int size = mCallbacks.size();
        for (int i = 0; i < size;) {
            WeakReference<IFontServiceCallback> weakRef = mCallbacks.get(i);
            IFontServiceCallback cb = weakRef.get();
            if (cb == null) {
                // Remove references that were GC'd
                logD("removing callback " + cb + " from list");
                mCallbacks.remove(i);
                size--;
            } else {
                if (cb == callback) {
                    containsCallback = true;
                }
                i++;
            }
        }
        if (!containsCallback) {
            logD("adding new callback");
            mCallbacks.add(new WeakReference<IFontServiceCallback>(callback));
        }
    }

    private void removeCallback(IFontServiceCallback callback) {
        logD("removeCallback, callback = " + callback);
        WeakReference<IFontServiceCallback> ref = null;
        int size = mCallbacks.size();
        for (int i = 0; i < size;) {
            WeakReference<IFontServiceCallback> weakRef = mCallbacks.get(i);
            IFontServiceCallback cb = weakRef.get();
            // Remove references that were GC'd as well
            if (cb == null || cb == callback) {
                logD("removing callback " + cb + " from list");
                mCallbacks.remove(i);
                size--;
            } else {
                i++;
            }
        }
    }

    private void removeFontFromSettings(String font) {
        final ContentResolver contentResolver = mContext.getContentResolver();
        String list = Settings.System.getStringForUser(contentResolver,
                Settings.System.FONT_LIST, UserHandle.USER_CURRENT);
        // Remove iff list is not empty and contains the font.
        if (!isEmpty(list) && list.contains(font)) {
            list = list.replace(font + "|", "");
            Settings.System.putStringForUser(contentResolver,
                Settings.System.FONT_LIST, list, UserHandle.USER_CURRENT);
        }
    }

    private String[] getAvailableFontsFromSettings() {
        String list = Settings.System.getStringForUser(mContext.getContentResolver(),
                Settings.System.FONT_LIST, UserHandle.USER_CURRENT);
        if (!isEmpty(list)) {
            return list.split("\\|");
        }
        return null;
    }

    private void updateCurrentInfoFromSettings() {
        final String info = Settings.System.getStringForUser(mContext.getContentResolver(),
                Settings.System.FONT_INFO, UserHandle.USER_CURRENT);
        // Fallback to defaults
        if (isEmpty(info)) {
            mFontInfo.loadDefaults();
        } else {
            final String[] infoArray = info.split("\\|");
            mFontInfo.fontName = infoArray[0];
            mFontInfo.fontPath = infoArray[1];
        }
    }

    private void applyFontInternal(FontInfo info) {
        final FontInfo defaultFontInfo = FontInfo.getDefaultFontInfo();
        // Make sure that we don't encounter any NPE's
        if (info == null) {
            info = defaultFontInfo;
        } else if (info.isEmpty()) {
            info.loadDefaults();
        }
        logD("applyFontInternal() fontInfo = " + info);

        final long ident = Binder.clearCallingIdentity();
        try {
            if (info.equals(defaultFontInfo)) {
                resetFonts();
            } else {
                if (sCurrentFontDir.isDirectory()) {
                    // Wipe anything inside
                    if (!FileUtils.deleteContents(sCurrentFontDir)) {
                        Log.e(TAG, "unable to delete contents in " +
                            sCurrentFontDir.getAbsolutePath());
                    }
                } else {
                    makeDir(sCurrentFontDir);
                }
                copyFont(info);
                mFontInfo.updateFrom(info);
                putCurrentFontInfoInSettings(info);
                refreshFonts();
            }
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    private void copyFont(FontInfo info) {
        logD("copyFont, info = " + info);
        // Directory containing the font
        final String dir = info.fontPath.substring(0,
            info.fontPath.lastIndexOf("/"));
        final File fontFile = new File(info.fontPath);
        final File fontXML = new File(dir, FONTS_XML);

        // Copy only if both ttf and xml exists.
        if (fontFile.isFile() && fontXML.isFile()) {
            try {
                FileUtils.copy(fontFile, new File(sCurrentFontDir,
                    appendExtension(info.fontName)));
                FileUtils.copy(fontXML, new File(sCurrentFontDir,
                    FONTS_XML));
            } catch (IOException e) {
                Log.e(TAG, "Error while copying files for " + info, e);
            }
        } else {
            Log.e(TAG, "Font file and / or config xml for " + info + " does not exist!");
        }
    }

    private void putCurrentFontInfoInSettings(FontInfo fontInfo) {
        Settings.System.putStringForUser(mContext.getContentResolver(), Settings.System.FONT_INFO,
                fontInfo.toDelimitedString(), UserHandle.USER_CURRENT);
    }

    private void addFontsInternal(final Map<String, ParcelFileDescriptor> map) {
        logD("addFontsInternal, map = " + map);

        if (map.isEmpty()) {
            // Even though the map is empty, notifying the callbacks is necessary for cleanup
            notifyCallbacks(cb -> cb.onFontsAdded(null));
            logD("addFontsInternal, input map is empty");
            return;
        }

        if (!sSavedFontsDir.isDirectory()) {
            makeDir(sSavedFontsDir);
        }
        final StringBuilder builder = new StringBuilder();
        final List<FontInfo> fontsAdded = new ArrayList<>(map.size());

        // Iterate over each element
        map.forEach((font, pfd) -> {
            try {
                logD("adding font " + font);
                final File fontDir = new File(sSavedFontsDir, font);
                if (makeDir(fontDir)) {
                    // Copy the font
                    final File fontFile = new File(fontDir, appendExtension(font));
                    final FileInputStream inStream = new FileInputStream(pfd.getFileDescriptor());
                    final FileOutputStream outStream = new FileOutputStream(fontFile);
                    FileUtils.copy(inStream, outStream);
                    inStream.close();
                    outStream.close();
                    createXMLConfig(font, fontDir);
                    if (!mFontMap.containsKey(font)) {
                        FontInfo fontInfo = new FontInfo(font, fontFile.getAbsolutePath());
                        mFontMap.put(font, fontInfo);
                        builder.append(font);
                        builder.append("|");
                        fontsAdded.add(fontInfo);
                    }
                } else {
                    Log.w(TAG, "Unable to create directory " + fontDir.getAbsolutePath());
                }
                pfd.close();
            } catch (IOException e) {
                Log.e(TAG, "IOException when processing ParcelFileDescriptor " + pfd, e);
            }
        });

        // If we weren't able to add any then notify callbacks and exit
        notifyCallbacks(cb -> cb.onFontsAdded(fontsAdded));
        if (fontsAdded.isEmpty()) {
            logD("addFontsInternal, no fonts were added");
            return;
        }
        logD("fonts added = " + fontsAdded);
        updateFontsInSettings(builder.toString());
    }

    private void createXMLConfig(String font, File dir) throws IOException {
        // Check if the template xml exists.
        // If not then there is no point in proceeding.
        if (sFontConfigXml.isFile()) {
            final File fontXML = new File(dir, FONTS_XML);
            try {
                if (mDocBuilder != null && mTransformer != null) {
                    final Document doc = mDocBuilder.parse(sFontConfigXml);
                    mXPath.reset();
                    final Node fontNode = (Node) mXPath.compile("/familyset/family/font")
                        .evaluate(doc, XPathConstants.NODE);
                    fontNode.setTextContent(appendExtension(font));
                    mTransformer.transform(new DOMSource(doc), new StreamResult(fontXML));
                } else {
                    Log.e(TAG, "DocumentBuilder or Transformer is null");
                }
            } catch (SAXException|XPathExpressionException|TransformerException e) {
                Log.e(TAG, "error creating xml config", e);
            }
        } else {
            Log.e(TAG, "Font config source file " + sFontConfigXml.getAbsolutePath()
                + " does not exist");
        }
    }

    private void updateFontsInSettings(String newList) {
        logD("updateFontsInSettings, newList " + newList);
        // Append the current list and update in settings
        final ContentResolver contentResolver = mContext.getContentResolver();
        String currentList = Settings.System.getStringForUser(contentResolver,
                Settings.System.FONT_LIST, UserHandle.USER_CURRENT);
        logD("currentList = " + currentList);
        if (!isEmpty(currentList)) {
            currentList += newList;
        } else {
            currentList = newList;
        }
        Settings.System.putStringForUser(contentResolver,
                Settings.System.FONT_LIST, currentList, UserHandle.USER_CURRENT);
        logD("updated list = " + currentList);
    }

    private void removeFontsInternal(final List<String> list) {
        logD("removeFontsInternal, list = " + list);
        if (list.isEmpty()) {
            // Even though the list is empty, notifying the callbacks is necessary for cleanup
            notifyCallbacks(cb -> cb.onFontsRemoved(null));
            logD("removeFontsInternal, input list is internal");
            return;
        }
        if (!sSavedFontsDir.isDirectory()) {
             Log.w(TAG, "Directory " + sSavedFontsDir.getAbsolutePath() + " does not exist");
             // Since the directory does not exist, notify that all fonts are removed
             final List<FontInfo> allFonts = new ArrayList<>(mFontMap.values());
             notifyCallbacks(cb -> cb.onFontsRemoved(allFonts));
             logD("removeFontsInternal, saved_fonts directory does not exist");
             return;
        }
        final List<FontInfo> fontsRemoved = new ArrayList<>(list.size());
        final ContentResolver contentResolver = mContext.getContentResolver();
        String currentList = Settings.System.getStringForUser(contentResolver,
                Settings.System.FONT_LIST, UserHandle.USER_CURRENT);

        // Whether we should reset font to system default
        boolean needsReset = false;

        // Iterate over each element
        for (String font: list) {
            // Skip elements not in our map
            if (mFontMap.containsKey(font)) {
                FontInfo fontInfo = mFontMap.get(font);
                File fontFile = new File(fontInfo.fontPath);
                if (fontFile.isFile()) {
                    if (fontFile.delete()) {
                        fontsRemoved.add(fontInfo);
                        // Reset font if the deleted font was the selected one
                        if (mFontInfo.equals(fontInfo)) {
                            needsReset = true;
                        }
                    } else {
                        Log.e(TAG, "Unable to delete file " + fontFile.getAbsolutePath());
                    }
                } else {
                    logD("File " + fontFile.getAbsolutePath() + " does not exist, skipping");
                }
                currentList = currentList.replace(font + "|", "");
                mFontMap.remove(font);
            }
        }
        logD("fonts removed = " + fontsRemoved);

        // If we weren't able to add any then notify callbacks and exit
        notifyCallbacks(cb -> cb.onFontsRemoved(fontsRemoved));
        // Reset if needed
        if (needsReset) {
            resetFonts();
        }
        if (fontsRemoved.isEmpty()) {
            logD("removeFontsInternal, no fonts were removed");
            return;
        }

        logD("updated list = " + currentList);
        logD("updated map = " + mFontMap);
        Settings.System.putStringForUser(contentResolver,
                Settings.System.FONT_LIST, currentList, UserHandle.USER_CURRENT);
    }

    private void notifyCallbacks(Consumer<IFontServiceCallback> consumer) {
        logD("notifyCallbacks, mCallbacks = " + mCallbacks);
        mCallbacks.stream()
            .map(weakRef -> weakRef.get())
            .filter(cb -> cb != null)
            .forEach(cb -> {
                try {
                    logD("notifying callback " + cb);
                    consumer.accept(cb);
                } catch (RemoteException e) {
                    Log.e(TAG, "RemoteException on notifying callback " + cb, e);
                }
            });
    }

    private void resetFonts() {
        if (!FileUtils.deleteContents(sCurrentFontDir)) {
            Log.e(TAG, "unable to delete contents in " +
                sCurrentFontDir.getAbsolutePath());
        }
        mFontInfo.loadDefaults();
        putCurrentFontInfoInSettings(mFontInfo);
        refreshFonts();
    }

    private void enforcePermissions() {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.ACCESS_FONT_MANAGER,
                "FontService");
    }

    private static String appendExtension(String font) {
        return font.endsWith(".ttf") ? font : font.concat(".ttf");
    }

    private static boolean setPermissions(File path, int perms) {
        return FileUtils.setPermissions(path, perms, -1, -1) == 0;
    }

    private static void setPermissionsRecursive(File file, int filePerms, int folderPerms) {
        if (file.exists() && !file.isDirectory()) {
            setPermissions(file, filePerms);
            return;
        }
        for (File f: file.listFiles()) {
            if (f.isDirectory()) {
                setPermissionsRecursive(f, filePerms, folderPerms);
                setPermissions(f, folderPerms);
            } else {
                setPermissions(f, filePerms);
            }
        }
        setPermissions(file, folderPerms);
    }

    private static boolean restoreconThemeDir() {
        return SELinux.restoreconRecursive(sThemeDir);
    }

    private static boolean makeDir(File dir) {
        if (dir.isDirectory()) {
            return true;
        }
        if (dir.mkdirs() && SELinux.restorecon(dir)) {
            return setPermissions(dir, S_IRWXU | S_IRGRP | S_IROTH | S_IXOTH);
        }
        return false;
    }

    private static boolean isEmpty(String str) {
        return str == null || str.isEmpty();
    }

    private static void logD(String msg) {
        if (DEBUG) {
            Log.d(TAG, msg);
        }
    }

    /**
     * Custom callback consumer
     */
    private interface Consumer<T> {
        void accept(T t) throws RemoteException;
    }
}
