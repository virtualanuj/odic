# kotlinx.serialization: the plugin generates serializers at compile time
# (not reflection-based), and the library ships its own consumer rules,
# but keep the DTOs' Companion objects explicitly as defense in depth —
# R8 has historically had edge cases around synthetic $serializer classes
# when aggressive optimization is combined with shrinking.
-keepclassmembers class com.urlinspector.data.reputation.** {
    *** Companion;
}
-keepclasseswithmembers class com.urlinspector.data.reputation.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# SQLDelight-generated database/query classes are constructed reflectively
# in a few internal paths; keep the generated package intact.
-keep class com.urlinspector.data.db.** { *; }

# Koin builds its dependency graph via a DSL, not reflection, but keep
# the DI module's declared types' constructors reachable defensively.
-keepclassmembers class com.urlinspector.app.** {
    public <init>(...);
}
