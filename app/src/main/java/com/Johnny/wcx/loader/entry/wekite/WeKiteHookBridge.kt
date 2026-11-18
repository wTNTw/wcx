@file:Suppress("LocalVariableName")

package com.Johnny.wcx.loader.entry.wekite

import android.util.Log
import androidx.annotation.Keep
import com.android.dx.DexMaker
import com.android.dx.FieldId
import com.android.dx.MethodId
import com.android.dx.TypeId
import dalvik.system.DexFile
import dev.ujhhgtg.reflekt.utils.isStatic
import com.Johnny.wcx.constants.PackageNames
import com.Johnny.wcx.loader.abc.IHookBridge
import com.Johnny.wcx.loader.abc.IHookBridge.IMemberHookCallback
import com.Johnny.wcx.loader.abc.IHookBridge.MemberUnhookHandle
import com.Johnny.wcx.utils.reflection.bool
import com.Johnny.wcx.utils.reflection.byte
import com.Johnny.wcx.utils.reflection.char
import com.Johnny.wcx.utils.reflection.double
import com.Johnny.wcx.utils.reflection.float
import com.Johnny.wcx.utils.reflection.int
import com.Johnny.wcx.utils.reflection.long
import com.Johnny.wcx.utils.reflection.short
import com.Johnny.wcx.utils.reflection.void
import java.lang.reflect.Constructor
import java.lang.reflect.Executable
import java.lang.reflect.Field
import java.lang.reflect.Member
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicLong

/**
 * IHookBridge implementation for Zygisk mode.
 *
 * For each hooked method, DexMaker generates a pair of methods preserving the
 * target's static/instance calling convention. Reference parameters and
 * return values are represented as Object.
 *
 *   bridge(T0 p0, T1 p1, …) -> R (instance receiver is implicit)
 *     Boxes primitive params into Object[], calls WeKiteHookBridgeRuntime.dispatch,
 *     unboxes the Object result back to R.
 *
 *   backup(T0 p0, T1 p1, …) -> R
 *     Placeholder body (throws). The native hook overwrites its ArtMethod with a
 *     copy of the target's original ArtMethod so calling it via reflection invokes
 *     the original unhooked code.
 *
 * The native art_hook_method() swaps the target's entry_point to the bridge's;
 * preserving the static bit is therefore part of the ABI contract.
 */
@Keep
class WeKiteHookBridge : IHookBridge {

    // ── IHookBridge metadata ──────────────────────────────────────────────────

    override val apiLevel: Int = android.os.Build.VERSION.SDK_INT
    override val frameworkName: String = "Zygisk"
    override val frameworkVersion: String = "v1"
    override val frameworkVersionCode: Long = 1
    override val isDeoptimizationSupported: Boolean = false
    override val supportsNativeMethodHook: Boolean = false

    private val hookCounterInternal = AtomicLong(0L)
    override val hookCounter: Long get() = hookCounterInternal.get()
    override val hookedMethods: Set<Member?> get() = WeKiteHookBridgeRuntime.hookedMembers()

    // ── Hook ─────────────────────────────────────────────────────────────────

    override fun hookMethod(
        member: Member,
        callback: IMemberHookCallback,
        priority: Int,
    ): MemberUnhookHandle {
        require(member is Method || member is Constructor<*>) {
            "hookMethod: unsupported member type ${member::class.java}"
        }
        require(!Modifier.isAbstract(member.modifiers)) {
            "hookMethod: cannot hook abstract member $member"
        }
        require(!Modifier.isNative(member.modifiers)) {
            "hookMethod: cannot hook native member $member"
        }
        require(!Proxy.isProxyClass(member.declaringClass)) {
            "hookMethod: cannot hook proxy member $member"
        }
        require(!Modifier.isInterface(member.declaringClass.modifiers)) {
            "hookMethod: cannot hook interface member $member"
        }

        // Fast path for an existing native hook. Registration and unhook remain
        // serialized, but DEX generation for a new hook must not hold this
        // process-wide lock: DexMaker/loadClass can take tens of milliseconds.
        val existingHandle = synchronized(WeKiteHookBridgeRuntime.hookLock) {
            val existingId = WeKiteHookBridgeRuntime.getHookId(member)
                ?: return@synchronized null
            val registration = WeKiteHookBridgeRuntime.addCallback(existingId, callback, priority)
            ArtUnhookHandle(member, callback, existingId, registration)
        }
        if (existingHandle != null) return existingHandle

        // Another thread may install this member while this runs. Its generated
        // class then becomes unreachable, and the second locked check below
        // attaches this callback to the already-installed native hook.
        val bridge = generateBridgePair(member)
        val (hookId, registration) = synchronized(WeKiteHookBridgeRuntime.hookLock) {
            val existingId = WeKiteHookBridgeRuntime.getHookId(member)
            val id = if (existingId != null) {
                existingId
            } else {
                val targetArt = nativeGetArtMethod(member)
                val backupArt = nativeGetArtMethod(bridge.backupMethod)
                val bridgeArt = nativeGetArtMethod(bridge.bridgeMethod)
                check(targetArt != 0L && backupArt != 0L && bridgeArt != 0L) {
                    "art_get_art_method returned 0 for $member"
                }

                // Register first so dispatch() has an entry before any calls.
                val newId = WeKiteHookBridgeRuntime.register(member, bridge.backupMethod, bridge.dexFile)

                // Write hookId into the generated class's static field.
                // Must happen before nativeHookMethod installs the entry_point.
                bridge.setHookId(newId)

                val rc = nativeHookMethod(targetArt, backupArt, bridgeArt, newId)
                if (rc != 0) {
                    WeKiteHookBridgeRuntime.unregister(newId)
                    error("art_hook_method failed (rc=$rc) for $member")
                }

                hookCounterInternal.incrementAndGet()
                newId
            }
            // Pair callback registration with the native hook lifecycle. A
            // concurrent final unhook must never remove the entry in the gap
            // between installing/looking up a hook and adding this callback.
            id to WeKiteHookBridgeRuntime.addCallback(id, callback, priority)
        }

        return ArtUnhookHandle(member, callback, hookId, registration)
    }

    // ── Deoptimize (unsupported) ──────────────────────────────────────────────

    override fun deoptimize(member: Member): Boolean = false

    /** Called after NativeLoader has loaded every module-provided native library. */
    internal fun hideLoadedModuleLibraries(): Boolean = nativeHideLoadedModuleLibraries()

    // ── Invoke original ───────────────────────────────────────────────────────

    override fun invokeOriginalMethod(method: Method, thisObject: Any?, args: Array<Any?>): Any? =
        WeKiteHookBridgeRuntime.invokeOriginal(method, thisObject, args)

    override fun <T> invokeOriginalConstructor(ctor: Constructor<T?>, thisObject: T, args: Array<Any?>) {
        WeKiteHookBridgeRuntime.invokeOriginal(ctor, thisObject, args)
    }

    override fun <T> newInstanceOrigin(constructor: Constructor<T?>, vararg args: Any): T {
        if (WeKiteHookBridgeRuntime.getHookId(constructor) == null) {
            @Suppress("UNCHECKED_CAST")
            return constructor.newInstance(*args) as T
        }

        // Constructor.newInstance() would re-enter the hooked ArtMethod. Match
        // LSPlant's origin path: allocate without initialization, then invoke
        // the copied original constructor through the backup ArtMethod.
        @Suppress("UNCHECKED_CAST")
        val instance = nativeAllocateInstance(constructor.declaringClass) as T
        val originArgs = arrayOfNulls<Any>(args.size)
        args.copyInto(originArgs)
        WeKiteHookBridgeRuntime.invokeOriginal(constructor, instance, originArgs)
        return instance
    }

    // ── BridgePair ────────────────────────────────────────────────────────────

    internal data class BridgePair(
        val bridgeMethod: Method,
        val backupMethod: Method,
        val dexFile: DexFile,
        /** Call this after registration to write the hookId into the generated class. */
        val setHookId: (Long) -> Unit,
    )

    // ── DexMaker bridge generation ────────────────────────────────────────────

    @Suppress("PrivatePropertyName")
    private val OBJ = TypeId.OBJECT as TypeId<Any>
    @Suppress("PrivatePropertyName")
    private val OBJ_ARR = TypeId.get<Array<Any?>>("[Ljava/lang/Object;")
    @Suppress("PrivatePropertyName")
    private val PLONG = TypeId.LONG
    @Suppress("PrivatePropertyName")
    private val INT_TID = TypeId.INT

    /**
     * Generates a class with two methods matching [target]'s calling convention.
     *
     * Bridge body:
     *   1. Read static field `hookId`.
     *   2. Allocate `Object[] args` of length == target param count.
     *   3. For each param: box primitive → Object, or use reference directly.
     *   4. Call `WeKiteHookBridgeRuntime.dispatch(hookId, self, args)`.
     *   5. Unbox the returned Object to target return type (or return void).
     *
     * Backup body: throws UnsupportedOperationException — ArtMethod is overwritten
     * by the native hook with a copy of target's original ArtMethod, so the body
     * is never actually executed.
     */
    internal fun generateBridgePair(target: Executable, trustDex: Boolean = true): BridgePair {
        val targetParams: Array<Class<*>> = target.parameterTypes
        val targetReturn: Class<*> = when (target) {
            is Method         -> target.returnType
            is Constructor<*> -> void // constructors are void at the ART level
            else              -> void
        }

        val suffix = java.lang.Long.toHexString(bridgeCounter.incrementAndGet())
        val fqn = $$"$${PackageNames.MODULE}.loader.entry.wekite.WkBr$$$suffix"
        val descriptor = "L${fqn.replace('.', '/')};"

        val dm = DexMaker()

        // ── TypeId helpers ────────────────────────────────────────────────────

        @Suppress("UNCHECKED_CAST")
        fun <T> tid(c: Class<T>): TypeId<T> = when (c) {
            int    -> TypeId.INT     as TypeId<T>
            long   -> TypeId.LONG    as TypeId<T>
            bool   -> TypeId.BOOLEAN as TypeId<T>
            byte   -> TypeId.BYTE    as TypeId<T>
            char   -> TypeId.CHAR    as TypeId<T>
            short  -> TypeId.SHORT   as TypeId<T>
            float  -> TypeId.FLOAT   as TypeId<T>
            double -> TypeId.DOUBLE  as TypeId<T>
            void   -> TypeId.VOID    as TypeId<T>
            else   -> TypeId.get(c)
        }

        val classId        = TypeId.get<Any>(descriptor)


        dm.declare(classId, fqn, Modifier.PUBLIC, OBJ)

        // static long hookId
        val hookIdFld = classId.getField(PLONG, "hookId") as FieldId<Any, Long>
        dm.declare(hookIdFld, Modifier.PUBLIC or Modifier.STATIC, 0L)

        // WeKiteHookBridgeRuntime.dispatch(long, Object, Object[]) -> Object
        val rtFqn = WeKiteHookBridgeRuntime::class.java.name.replace('.', '/')
        val rtType = TypeId.get<Any>("L$rtFqn;")
        val dispatchMid = rtType.getMethod(OBJ, "dispatch", PLONG, OBJ, OBJ_ARR) as MethodId<Any, Any>

        val isStatic = target is Method && target.isStatic

        // Deliberately avoids referring to host-private classes from the
        // generated DEX. Primitive types stay exact; every reference is Object.
        val dexParamClasses = Array(targetParams.size) { i ->
            if (targetParams[i].isPrimitive) targetParams[i] else Any::class.java
        }
        val dexReturnClass = when {
            targetReturn == Void.TYPE || targetReturn.isPrimitive -> targetReturn
            else -> Any::class.java
        }

        val allParamTids: Array<TypeId<*>> = Array(targetParams.size) { i ->
            tid(dexParamClasses[i])
        }
        val retTid = tid(dexReturnClass)

        // Wrapper types for boxing/unboxing
        data class BoxInfo(
            val wrapperTid: TypeId<*>,
            val valueOfMid: MethodId<*, *>,
            val unboxMid: MethodId<*, *>,
        )

        fun boxInfo(prim: Class<*>): BoxInfo? {
            if (!prim.isPrimitive || prim == void) return null
            // 注意：本仓 Classes.kt 里的 BInt/BBool 是 Int::class.java（原始类型 I/Z），
            // 与 WeKite 的 Int::class.javaObjectType（包装类 Ljava/lang/Integer;）语义相反！
            // 这里必须显式取 javaObjectType，否则 wTid 会变成 TypeId.INT('I')，
            // 生成的 check-cast / valueOf 会报 Invalid descriptor for class_idx: 'I'/'Z'。
            @Suppress("UNCHECKED_CAST")
            val wrapperClass: Class<Any> = when (prim) {
                int     -> Int::class.javaObjectType
                long    -> Long::class.javaObjectType
                bool    -> Boolean::class.javaObjectType
                byte    -> Byte::class.javaObjectType
                char    -> Char::class.javaObjectType
                short   -> Short::class.javaObjectType
                float   -> Float::class.javaObjectType
                double  -> Double::class.javaObjectType
                else    -> return null
            } as Class<Any>
            val unboxName = when (prim) {
                int     -> "intValue"
                long    -> "longValue"
                bool    -> "booleanValue"
                byte    -> "byteValue"
                char    -> "charValue"
                short   -> "shortValue"
                float   -> "floatValue"
                double  -> "doubleValue"
                else -> return null
            }
            val wTid = tid(wrapperClass)
            val primTid = tid(prim)
            @Suppress("UNCHECKED_CAST")
            return BoxInfo(
                wrapperTid = wTid,
                valueOfMid = wTid.getMethod(wTid, "valueOf", primTid),
                unboxMid   = wTid.getMethod(primTid, unboxName),
            )
        }

        // ── Generate bridge / backup ──────────────────────────────────────────

        @Suppress("UNCHECKED_CAST")
        fun declareBridgeOrBackup(name: String, isBackup: Boolean) {
            val mid = classId.getMethod(retTid, name, *allParamTids) as MethodId<Any, Any>
            val modifiers = Modifier.PUBLIC or if (isStatic) Modifier.STATIC else 0
            val code = dm.declare(mid, modifiers)

            if (isBackup) {
                val usoeTid = TypeId.get<UnsupportedOperationException>(
                    "Ljava/lang/UnsupportedOperationException;")
                val strTid = TypeId.STRING
                val exLocal = code.newLocal(usoeTid)
                val msgLocal = code.newLocal(strTid)
                code.loadConstant(msgLocal, "backup not initialized")
                @Suppress("UNCHECKED_CAST")
                val usoeCtorMid = usoeTid.getConstructor(strTid) as MethodId<UnsupportedOperationException, Void>
                code.newInstance(exLocal, usoeCtorMid, msgLocal)
                code.throwValue(exLocal)
                return
            }

            // DexMaker fixes register positions as soon as the first instruction
            // touches a Local, so every scratch local must be allocated up front.
            val receiver = if (isStatic) null else code.getThis(classId)
            @Suppress("UNCHECKED_CAST")
            val parameterLocals = Array(targetParams.size) { i ->
                code.getParameter(i, allParamTids[i] as TypeId<Any>)
            }
            val parameterBoxInfos = Array(targetParams.size) { i ->
                boxInfo(targetParams[i])
            }
            val hookIdLocal = code.newLocal(PLONG)
            val selfLocal = code.newLocal(OBJ)
            val sizeLocal = code.newLocal(INT_TID)
            val argsLocal = code.newLocal(OBJ_ARR)
            val indexLocals = Array(targetParams.size) {
                code.newLocal(INT_TID)
            }
            @Suppress("UNCHECKED_CAST")
            val argumentLocals = Array(targetParams.size) { i ->
                val argumentType = parameterBoxInfos[i]?.wrapperTid ?: OBJ
                code.newLocal(argumentType as TypeId<Any>)
            }
            val rawResultLocal = code.newLocal(OBJ)
            val returnBoxInfo = boxInfo(targetReturn)
            @Suppress("UNCHECKED_CAST")
            val returnWrapperLocal = returnBoxInfo?.let {
                code.newLocal(it.wrapperTid as TypeId<Any>)
            }
            @Suppress("UNCHECKED_CAST")
            val primitiveResultLocal = returnBoxInfo?.let {
                code.newLocal(tid(targetReturn) as TypeId<Any>)
            }

            // 1. Read hookId.
            code.sget(hookIdFld, hookIdLocal)

            // 2. Get self for dispatch. The receiver is implicit for an
            // instance method and absent for a static method.
            if (receiver == null) {
                @Suppress("UNCHECKED_CAST")
                code.loadConstant(selfLocal as com.android.dx.Local<Nothing?>, null)
            } else {
                code.cast(selfLocal, receiver)
            }

            // 3. Allocate Object[] args.
            code.loadConstant(sizeLocal, targetParams.size)
            code.newArray(argsLocal, sizeLocal)

            // 4. Box each param into args[i].
            for (i in targetParams.indices) {
                val paramLocal = parameterLocals[i]
                val idxLocal = indexLocals[i]
                val argumentLocal = argumentLocals[i]
                code.loadConstant(idxLocal, i)

                val bi = parameterBoxInfos[i]
                if (bi != null) {
                    @Suppress("UNCHECKED_CAST")
                    code.invokeStatic(
                        bi.valueOfMid as MethodId<Any, Any>,
                        argumentLocal,
                        paramLocal,
                    )
                } else {
                    code.cast(argumentLocal, paramLocal)
                }
                code.aput(argsLocal, idxLocal, argumentLocal)
            }

            // 5. Call dispatch(hookId, self, args) -> Object.
            code.invokeStatic(dispatchMid, rawResultLocal, hookIdLocal, selfLocal, argsLocal)

            // 6. Return (unboxing as needed).
            when {
                targetReturn == void -> code.returnVoid()

                targetReturn.isPrimitive -> {
                    val bi = checkNotNull(returnBoxInfo)
                    val wLocal = checkNotNull(returnWrapperLocal)
                    val primLocal = checkNotNull(primitiveResultLocal)
                    code.cast(wLocal, rawResultLocal)
                    @Suppress("UNCHECKED_CAST")
                    code.invokeVirtual(
                        bi.unboxMid as MethodId<Any, Any>,
                        primLocal,
                        wLocal,
                    )
                    code.returnValue(primLocal)
                }

                else -> code.returnValue(rawResultLocal)
            }
        }

        declareBridgeOrBackup("bridge", isBackup = false)
        declareBridgeOrBackup("backup", isBackup = true)

        // ── Load generated DEX ────────────────────────────────────────────────

        val dexBytes = dm.generate()
        val parentLoader = WeKiteHookBridge::class.java.classLoader
            ?: ClassLoader.getSystemClassLoader()
        val dexCtor = DexFile::class.java.declaredConstructors
            .firstOrNull { ctor ->
                ctor.parameterCount == 3 &&
                    ctor.parameterTypes[0] == Array<ByteBuffer>::class.java
            }
            ?: error("DexFile(ByteBuffer[], ClassLoader, Element[]) constructor not found")
        dexCtor.isAccessible = true
        val dexFile = dexCtor.newInstance(
            arrayOf(ByteBuffer.wrap(dexBytes)),
            parentLoader,
            null,
        ) as DexFile
        if (trustDex) {
            check(nativeTrustDexFile(dexFile)) { "failed to trust generated hook dex" }
        }
        @Suppress("DEPRECATION")
        val genClass = dexFile.loadClass(fqn, parentLoader)

        // Reference parameters are Object in the generated DEX, so reflection
        // must look up the same erased signature.
        val reflectParams: Array<Class<*>> = dexParamClasses
        val bridgeMethod = genClass.getDeclaredMethod("bridge", *reflectParams)
        val backupMethod = genClass.getDeclaredMethod("backup", *reflectParams)
        bridgeMethod.isAccessible = true
        backupMethod.isAccessible = true

        val hookIdStaticFld: Field = genClass.getDeclaredField("hookId").also { it.isAccessible = true }

        return BridgePair(
            bridgeMethod = bridgeMethod,
            backupMethod = backupMethod,
            dexFile = dexFile,
            setHookId = { id -> hookIdStaticFld.setLong(null, id) },
        )
    }

    // ── Native JNI declarations (registered by the resident Zygisk SO) ───────

    companion object {
        private const val TAG = "WeKiteHookBridge"
        private val bridgeCounter = AtomicLong(0L)

        @JvmStatic private external fun nativeInitialize(): Boolean
        internal fun initializeNativeBackend(): Boolean = nativeInitialize()

        @JvmStatic private external fun nativeGetArtMethod(executable: Executable): Long
        @JvmStatic private external fun nativeHookMethod(
            targetArt: Long, backupArt: Long, bridgeArt: Long, hookId: Long): Int
        @JvmStatic private external fun nativeUnhookMethod(targetArt: Long, backupArt: Long): Int
        @JvmStatic private external fun nativeTrustDexFile(dexFile: DexFile): Boolean
        @JvmStatic private external fun nativeAllocateInstance(clazz: Class<*>): Any
        @JvmStatic private external fun nativeHideLoadedModuleLibraries(): Boolean
    }

    // ── Unhook handle ─────────────────────────────────────────────────────────

    private inner class ArtUnhookHandle(
        override val member: Member,
        override val callback: IMemberHookCallback,
        private val hookId: Long,
        private val registration: WeKiteHookBridgeRuntime.PrioritizedCallback,
    ) : MemberUnhookHandle {

        @Volatile private var active = true
        override val isHookActive: Boolean get() = active

        override fun unhook() {
            synchronized(WeKiteHookBridgeRuntime.hookLock) {
                if (!active) return
                if (!WeKiteHookBridgeRuntime.removeCallback(hookId, registration)) {
                    active = false
                    return
                }
                val entry = WeKiteHookBridgeRuntime.getEntry(hookId)
                if (entry == null || entry.callbacks.isNotEmpty()) {
                    active = false
                    return
                }

                val targetArt = nativeGetArtMethod(member as Executable)
                val backupArt = nativeGetArtMethod(entry.backupMethod)
                if (targetArt == 0L || backupArt == 0L ||
                    nativeUnhookMethod(targetArt, backupArt) != 0
                ) {
                    if (!WeKiteHookBridgeRuntime.restoreCallback(hookId, registration)) {
                        active = false
                        Log.e(TAG, "failed to unhook $member; callback restoration also failed")
                    } else {
                        Log.e(TAG, "failed to unhook $member; callback restored for retry")
                    }
                    return
                }
                WeKiteHookBridgeRuntime.retire(hookId)
                hookCounterInternal.decrementAndGet()
                active = false
            }
        }
    }
}
