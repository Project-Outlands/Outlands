package top.outlands.gradle.helpers

interface ProjectConstants {
    List<String> JVM_ARGUMENTS = [
        // The loader requires Java 25, so the flags the launcher profiles carry are safe here too; a dev run should
        // behave like a shipped one.
        '-XX:+UseCompactObjectHeaders',
        '-XX:+ExplicitGCInvokesConcurrent',
        '--add-opens=java.base/jdk.internal.loader=ALL-UNNAMED',
        '--add-opens=java.base/jdk.internal.reflect=ALL-UNNAMED',
        '--add-opens=java.base/java.lang=ALL-UNNAMED',
        '--add-opens=java.base/java.lang.reflect=ALL-UNNAMED'
    ]

    List<String> COMPILER_JVM_ARGUMENTS = [
        '--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED',
        '--add-exports=java.base/jdk.internal.reflect=ALL-UNNAMED'
    ]

    Map<String, Object> PATCHER_POST_PROCESSOR = [
        tool: 'net.minecraftforge:mcpcleanup:2.3.2:fatjar',
        repo: 'https://maven.minecraftforge.net/',
        args: ['--input', '{input}', '--output', '{output}']
    ]
}