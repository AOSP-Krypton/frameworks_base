package com.krypton.systemui.dagger;

import com.android.systemui.dagger.DefaultComponentBinder;
import com.android.systemui.dagger.DependencyProvider;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dagger.SystemUIBinder;
import com.android.systemui.dagger.SysUIComponent;
import com.android.systemui.dagger.SystemUIModule;

import dagger.Subcomponent;

@SysUISingleton
@Subcomponent(modules = {
        DefaultComponentBinder.class,
        DependencyProvider.class,
        SystemUIBinder.class,
        SystemUIModule.class,
        KryptonSystemUIModule.class})
public interface KryptonSysUIComponent extends SysUIComponent {
    @SysUISingleton
    @Subcomponent.Builder
    interface Builder extends SysUIComponent.Builder {
        KryptonSysUIComponent build();
    }
}
