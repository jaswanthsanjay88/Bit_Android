# Consumer ProGuard Rules for NeuronPacket
# These rules are applied to projects that use this library

# Keep NeuronPacket Manager and Native classes
-keep public class com.neuronpacket.NeuronPacketManager {
    public *;
}
-keep public class com.neuronpacket.NeuronPacketNative {
    public *;
    native <methods>;
}

# Keep all model classes
-keep public class com.neuronpacket.LoadingMode { *; }
-keep public class com.neuronpacket.Permission { *; }
-keep public class com.neuronpacket.ExportResult { *; }
-keep public class com.neuronpacket.ImportResult { *; }
-keep public class com.neuronpacket.AuthResult { *; }
-keep public class com.neuronpacket.UserCredentials { *; }
-keep public class com.neuronpacket.PacketMetadata { *; }
-keep public class com.neuronpacket.ExportConfig { *; }
-keep public class com.neuronpacket.PacketSession { *; }

# Keep enum entries
-keepclassmembers enum com.neuronpacket.* {
    <fields>;
    public static **[] values();
    public static ** valueOf(java.lang.String);
}