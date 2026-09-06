package com.velox.mixin.client;

import com.velox.VeloxFollow;
import net.minecraft.client.OptionInstance;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 监听原版 OptionInstance 的修改（【五】原版设置跟随）。
 *
 * <p>玩家在设置界面改任意选项都会走到这里，转发给 VeloxFollow 记录。
 * require = 0：方法名若在 1.21.11 里不是 set/setValue，则静默跳过，绝不崩溃。</p>
 */
@Mixin(OptionInstance.class)
public abstract class OptionInstanceMixin<T> {

    @Inject(method = {"setValue", "set"}, at = @At("TAIL"), require = 0)
    private void velox$onSet(T value, CallbackInfo ci) {
        VeloxFollow.onOptionChanged((OptionInstance<?>) (Object) this, value);
    }
}
