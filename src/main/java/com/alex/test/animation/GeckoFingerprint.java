package com.alex.test.animation;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ConstantDynamic;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Short codes for the GeckoLib posing code our fast posing copied (plan step 0a,
 * docs/plans/2026-10-22-hundred-villagers-fps.md). {@code FastController} and
 * {@code FastAnimationProcessor} are line-for-line copies of GeckoLib 4.8.4 methods;
 * they give the same pose only while GeckoLib's own copies are still that code. Instead
 * of a list of version numbers, the game reads those methods out of the GeckoLib that is
 * loaded, boils each one down to a short code and compares with the codes stored from
 * 4.8.4 ({@code fastpose-fingerprint.txt} in the mod's resources).
 *
 * <p>A code is a hash of the method's instructions: opcodes, operands, names, numbers and
 * jump targets, with line numbers, local variable names and stack frames left out (they
 * change between compilers without changing what the code does). Lambda methods belong
 * to the method they were written in and have their compiler-given numbers replaced, so
 * a renumbering alone changes nothing. Fields are listed by name and type. Only members
 * present in the stored file are compared: a GeckoLib that adds methods or fields is
 * still "the same"; one that removes or rewrites a watched member is not.
 *
 * <p>Pure Java plus ASM's class reader (NeoForge ships it); no Minecraft, so it has a
 * JUnit test and runs on its own: {@code java -cp <classes>;asm.jar
 * com.alex.test.animation.GeckoFingerprint <geckolib.jar> [--version 4.8.4]
 * [--write <file>] [--compare <file>]}. Exit code 1 when a compare finds differences.
 */
public final class GeckoFingerprint {

    /** One watched GeckoLib class: the named methods (null = every method) and always every field. */
    public record Watch(String className, Set<String> methods) {
        boolean watches(String methodBaseName) {
            return methods == null || methods.contains(methodBaseName);
        }
    }

    /** The GeckoLib classes and methods the fast posing copies came from or lean on. */
    public static final List<Watch> WATCHED = List.of(
            new Watch("software.bernie.geckolib.animation.AnimationController", Set.of(
                    "process", "processCurrentAnimation", "getCurrentKeyFrameLocation", "createInitialQueues",
                    "saveSnapshotsForAnimation", "getAnimationPointAtTick")),
            new Watch("software.bernie.geckolib.animation.AnimationProcessor", Set.of(
                    "tickAnimation", "updateBoneSnapshots", "resetBoneTransformationMarkers",
                    "isSuspectedCompletedRotation")),
            new Watch("software.bernie.geckolib.model.GeoModel", Set.of("handleAnimations", "getBakedModel")),
            new Watch("software.bernie.geckolib.animation.keyframe.BoneAnimationQueue", null),
            new Watch("software.bernie.geckolib.animation.keyframe.AnimationPoint", null),
            new Watch("software.bernie.geckolib.animation.keyframe.KeyframeLocation", null),
            new Watch("software.bernie.geckolib.animation.state.BoneSnapshot", null),
            new Watch("software.bernie.geckolib.cache.object.GeoBone", null));

    /** The stored codes: the GeckoLib version they were read from and one code per member. */
    public record Stored(String version, Map<String, String> codes) {}

    /** The code of a class that could not be read at all. */
    public static final String MISSING = "missing";

    private static final String VERSION_KEY = "geckolib";

    private GeckoFingerprint() {}

    // ---------------------------------------------------------------------------------------------
    // Codes
    // ---------------------------------------------------------------------------------------------

    /** Key ("class#method(desc)" or "class.field desc") to code for every watched member of one class. */
    public static Map<String, String> of(byte[] classBytes, Watch watch) {
        ClassReader reader = new ClassReader(classBytes);
        ClassHasher hasher = new ClassHasher(reader.getClassName().replace('/', '.'), watch);
        reader.accept(hasher, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return hasher.codes;
    }

    /**
     * Codes for every watched class. {@code classBytes} gives a class file's bytes by class
     * name, or null; a class with none gets one entry, its name to {@link #MISSING}.
     */
    public static Map<String, String> all(Function<String, byte[]> classBytes) {
        Map<String, String> codes = new TreeMap<>();
        for (Watch watch : WATCHED) {
            byte[] bytes;
            try {
                bytes = classBytes.apply(watch.className());
            } catch (RuntimeException e) {
                bytes = null;
            }
            if (bytes == null) {
                codes.put(watch.className(), MISSING);
            } else {
                codes.putAll(of(bytes, watch));
            }
        }
        return codes;
    }

    /** Codes for every watched class inside a GeckoLib jar. */
    public static Map<String, String> fromJar(Path jar) throws IOException {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            return all(name -> {
                ZipEntry entry = zip.getEntry(name.replace('.', '/') + ".class");
                if (entry == null) return null;
                try (InputStream in = zip.getInputStream(entry)) {
                    return in.readAllBytes();
                } catch (IOException e) {
                    return null;
                }
            });
        }
    }

    /**
     * The stored members whose code is not the same in {@code loaded}: "key (changed)" or
     * "key (missing)", in stored order. Members only {@code loaded} has are not differences.
     */
    public static List<String> differences(Map<String, String> stored, Map<String, String> loaded) {
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, String> entry : stored.entrySet()) {
            String key = entry.getKey();
            if (MISSING.equals(entry.getValue())) continue;      // never stored on purpose; be lenient
            String now = loaded.get(key);
            if (now == null) {
                out.add(key + " (missing)");
            } else if (!now.equals(entry.getValue())) {
                out.add(key + " (changed)");
            }
        }
        return out;
    }

    // ---------------------------------------------------------------------------------------------
    // The stored file
    // ---------------------------------------------------------------------------------------------

    /** The file's text: a comment, the version, then one "key=code" line per member, sorted. */
    public static String format(Stored stored) {
        StringBuilder out = new StringBuilder();
        out.append("# GeckoLib posing code the fast villager posing copied: one code per method and field.\n");
        out.append("# Written by com.alex.test.animation.GeckoFingerprint; see docs/plans/2026-10-22-hundred-villagers-fps.md.\n");
        out.append(VERSION_KEY).append('=').append(stored.version()).append('\n');
        for (Map.Entry<String, String> entry : new TreeMap<>(stored.codes()).entrySet())
            out.append(entry.getKey()).append('=').append(entry.getValue()).append('\n');
        return out.toString();
    }

    public static Stored parse(Reader text) throws IOException {
        String version = "unknown";
        Map<String, String> codes = new TreeMap<>();
        try (BufferedReader reader = new BufferedReader(text)) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.strip();
                if (line.isEmpty() || line.startsWith("#")) continue;
                int at = line.lastIndexOf('=');
                if (at <= 0) continue;
                String key = line.substring(0, at);
                String value = line.substring(at + 1);
                if (key.equals(VERSION_KEY)) version = value;
                else codes.put(key, value);
            }
        }
        return new Stored(version, codes);
    }

    public static Stored parse(String text) throws IOException {
        return parse(new StringReader(text));
    }

    // ---------------------------------------------------------------------------------------------
    // Command line: java ... GeckoFingerprint <jar> [--version v] [--write file] [--compare file]
    // ---------------------------------------------------------------------------------------------

    public static void main(String[] args) throws IOException {
        if (args.length == 0) {
            System.err.println("usage: GeckoFingerprint <geckolib.jar> [--version 4.8.4] [--write <file>] [--compare <file>]");
            System.exit(2);
        }
        Path jar = Path.of(args[0]);
        String version = versionFromName(jar.getFileName().toString());
        Path write = null, compare = null;
        for (int i = 1; i < args.length; i++) {
            switch (args[i]) {
                case "--version" -> version = args[++i];
                case "--write" -> write = Path.of(args[++i]);
                case "--compare" -> compare = Path.of(args[++i]);
                default -> {
                    System.err.println("unknown option " + args[i]);
                    System.exit(2);
                }
            }
        }
        Map<String, String> codes = fromJar(jar);
        Stored now = new Stored(version, codes);
        if (write != null) {
            Files.writeString(write, format(now), StandardCharsets.UTF_8);
            System.out.println("wrote " + codes.size() + " codes from GeckoLib " + version + " to " + write);
        } else if (compare == null) {
            System.out.print(format(now));
        }
        if (compare != null) {
            Stored stored = parse(Files.readString(compare, StandardCharsets.UTF_8));
            List<String> changed = differences(stored.codes(), codes);
            System.out.println("GeckoLib " + version + " against stored " + stored.version() + ": "
                    + stored.codes().size() + " members compared, " + changed.size() + " different");
            for (String line : changed) System.out.println("  " + line);
            if (!changed.isEmpty()) System.exit(1);
        }
    }

    /** "geckolib-neoforge-1.21.1-4.9.3.jar" gives "4.9.3"; anything else "unknown". */
    static String versionFromName(String fileName) {
        String name = fileName.endsWith(".jar") ? fileName.substring(0, fileName.length() - 4) : fileName;
        int dash = name.lastIndexOf('-');
        if (dash < 0 || dash == name.length() - 1) return "unknown";
        String tail = name.substring(dash + 1);
        return Character.isDigit(tail.charAt(0)) ? tail : "unknown";
    }

    // ---------------------------------------------------------------------------------------------
    // Hashing
    // ---------------------------------------------------------------------------------------------

    /** The method a name belongs to: "lambda$process$4" is part of "process"; anything else is itself. */
    static String baseName(String methodName) {
        if (!methodName.startsWith("lambda$")) return methodName;
        int second = methodName.indexOf('$', "lambda$".length());
        return second < 0 ? methodName : methodName.substring("lambda$".length(), second);
    }

    /** A lambda's name without the number the compiler gave it: "lambda$process$4" to "lambda$process$". */
    static String withoutLambdaNumber(String methodName) {
        if (!methodName.startsWith("lambda$")) return methodName;
        int second = methodName.indexOf('$', "lambda$".length());
        return second < 0 ? methodName : methodName.substring(0, second + 1);
    }

    static String hash(CharSequence text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] sum = digest.digest(text.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(16);
            for (int i = 0; i < 8; i++) hex.append(String.format("%02x", sum[i]));
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static final class ClassHasher extends ClassVisitor {
        final Map<String, String> codes = new TreeMap<>();
        private final String owner;
        private final Watch watch;
        /** Lambdas of one method, numbered in the order the class file lists them. */
        private final Map<String, Integer> lambdaSeq = new HashMap<>();

        ClassHasher(String owner, Watch watch) {
            super(Opcodes.ASM9);
            this.owner = owner;
            this.watch = watch;
        }

        @Override
        public FieldVisitor visitField(int access, String name, String descriptor, String signature, Object value) {
            codes.put(owner + "." + name + " " + descriptor,
                    (access & Opcodes.ACC_STATIC) != 0 ? "field static" : "field");
            return null;
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
                                         String[] exceptions) {
            if (!watch.watches(baseName(name))) return null;
            String keyName = name;
            if (name.startsWith("lambda$")) {
                String base = withoutLambdaNumber(name);
                int seq = lambdaSeq.merge(base, 1, Integer::sum);
                keyName = base + "#" + seq;
            }
            String key = owner + "#" + keyName + descriptor;
            return new MethodHasher() {
                @Override
                public void visitEnd() {
                    codes.put(key, hash(out));
                }
            };
        }
    }

    /** Writes one method's instructions as text, in a form that does not change between compilers of the same source. */
    private static class MethodHasher extends MethodVisitor {
        final StringBuilder out = new StringBuilder();
        private final Map<Label, Integer> labels = new IdentityHashMap<>();

        MethodHasher() {
            super(Opcodes.ASM9);
        }

        private String label(Label label) {
            return "L" + labels.computeIfAbsent(label, k -> labels.size());
        }

        private static String handle(Handle handle) {
            return "H" + handle.getTag() + " " + handle.getOwner() + "." + withoutLambdaNumber(handle.getName())
                    + handle.getDesc();
        }

        private static String constant(Object value) {
            if (value instanceof Type type) return "T" + type.getDescriptor();
            if (value instanceof Handle handle) return handle(handle);
            if (value instanceof ConstantDynamic dynamic) {
                StringBuilder text = new StringBuilder("CD" + dynamic.getName() + dynamic.getDescriptor() + " "
                        + handle(dynamic.getBootstrapMethod()));
                for (int i = 0; i < dynamic.getBootstrapMethodArgumentCount(); i++)
                    text.append(' ').append(constant(dynamic.getBootstrapMethodArgument(i)));
                return text.toString();
            }
            if (value instanceof String string) return "S\"" + string.replace("\n", "\\n") + "\"";
            return value.getClass().getSimpleName() + ":" + value;
        }

        @Override
        public void visitInsn(int opcode) {
            out.append(opcode).append('\n');
        }

        @Override
        public void visitIntInsn(int opcode, int operand) {
            out.append(opcode).append(' ').append(operand).append('\n');
        }

        @Override
        public void visitVarInsn(int opcode, int varIndex) {
            out.append(opcode).append(" v").append(varIndex).append('\n');
        }

        @Override
        public void visitTypeInsn(int opcode, String type) {
            out.append(opcode).append(' ').append(type).append('\n');
        }

        @Override
        public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
            out.append(opcode).append(' ').append(owner).append('.').append(name).append(' ').append(descriptor).append('\n');
        }

        @Override
        public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean isInterface) {
            out.append(opcode).append(' ').append(owner).append('.').append(withoutLambdaNumber(name))
                    .append(descriptor).append(isInterface ? " i" : "").append('\n');
        }

        @Override
        public void visitInvokeDynamicInsn(String name, String descriptor, Handle bootstrapMethodHandle,
                                           Object... bootstrapMethodArguments) {
            out.append("indy ").append(name).append(descriptor).append(' ').append(handle(bootstrapMethodHandle));
            for (Object argument : bootstrapMethodArguments) out.append(' ').append(constant(argument));
            out.append('\n');
        }

        @Override
        public void visitJumpInsn(int opcode, Label label) {
            out.append(opcode).append(' ').append(label(label)).append('\n');
        }

        @Override
        public void visitLabel(Label label) {
            out.append(label(label)).append(":\n");
        }

        @Override
        public void visitLdcInsn(Object value) {
            out.append("ldc ").append(constant(value)).append('\n');
        }

        @Override
        public void visitIincInsn(int varIndex, int increment) {
            out.append("iinc v").append(varIndex).append(' ').append(increment).append('\n');
        }

        @Override
        public void visitTableSwitchInsn(int min, int max, Label dflt, Label... labels) {
            out.append("tableswitch ").append(min).append(' ').append(max).append(' ').append(label(dflt));
            for (Label label : labels) out.append(' ').append(label(label));
            out.append('\n');
        }

        @Override
        public void visitLookupSwitchInsn(Label dflt, int[] keys, Label[] labels) {
            out.append("lookupswitch ").append(label(dflt));
            for (int i = 0; i < keys.length; i++) out.append(' ').append(keys[i]).append(':').append(label(labels[i]));
            out.append('\n');
        }

        @Override
        public void visitMultiANewArrayInsn(String descriptor, int numDimensions) {
            out.append("multianewarray ").append(descriptor).append(' ').append(numDimensions).append('\n');
        }

        @Override
        public void visitTryCatchBlock(Label start, Label end, Label handler, String type) {
            out.append("try ").append(label(start)).append(' ').append(label(end)).append(' ').append(label(handler))
                    .append(' ').append(type).append('\n');
        }

        // Left out on purpose (SKIP_DEBUG and SKIP_FRAMES drop most of them anyway): line numbers,
        // local variable names, stack map frames, max stack and locals, parameter names, annotations.
    }
}
