package com.krypton.systemui;

import android.content.Context;

import com.krypton.systemui.dagger.KryptonGlobalRootComponent;
import com.krypton.systemui.dagger.DaggerKryptonGlobalRootComponent;

import com.android.systemui.SystemUIFactory;
import com.android.systemui.dagger.GlobalRootComponent;

public class KryptonSystemUIFactory extends SystemUIFactory {
    @Override
    protected GlobalRootComponent buildGlobalRootComponent(Context context) {
        return DaggerKryptonGlobalRootComponent.builder()
                .context(context)
                .build();
    }
}
