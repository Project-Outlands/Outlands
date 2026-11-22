package top.outlands.gradle.helpers.tasks

import groovy.json.JsonSlurper
import org.gradle.api.GradleException
import org.gradle.api.artifacts.Configuration

import java.security.MessageDigest
import java.text.SimpleDateFormat

class Util {
	static void init() {
		File.metaClass.sha1 = { ->
			MessageDigest md = MessageDigest.getInstance('SHA-1')
			delegate.eachByte 4096, {byte[] bytes, int size ->
				md.update(bytes, 0, size)
			}
			return md.digest().collect {String.format "%02x", it}.join()
		}
	
		File.metaClass.json = { -> new JsonSlurper().parseText(delegate.text as String) }
		
		Date.metaClass.iso8601 = { ->
			def format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ")
			def result = format.format(delegate)
			return result[0..21] + ':' + result[22..-1]
		}
        
        String.metaClass.rsplit = { String del, int limit = -1 ->
            def lst = new ArrayList()
            def x = 0, idx
            def tmp = delegate
            while ((idx = tmp.lastIndexOf(del)) != -1 && (limit == -1 || x++ < limit)) {
                lst.add(0, tmp.substring(idx + del.length(), tmp.length()))
                tmp = tmp.substring(0, idx)
            }
            lst.add(0, tmp)
            return lst
        }
	}
	
	static String[] getClasspath(project, libs, artifact) {
		def ret = []
		artifactTree(project, artifact).each { key, lib ->
			libs[lib.name] = lib
			if (lib.name != artifact)
				ret.add(lib.name)
		}
		return ret
	}
	
	static def getArtifacts(Configuration config, boolean classifiers) {
		def ret = [:]
		config.resolvedConfiguration.resolvedArtifacts.each {
			def art = [
				group: it.moduleVersion.id.group,
				name: it.moduleVersion.id.name,
				version: it.moduleVersion.id.version,
				classifier: it.classifier,
				extension: it.extension,
				file: it.file
			]
			def key = art.group + ':' + art.name
			def folder = "${art.group.replace('.', '/')}/${art.name}/${art.version}/"
			def filename = "${art.name}-${art.version}"
			if (art.classifier != null)
				filename += "-${art.classifier}"
			filename += ".${art.extension}"
			def path = "${folder}${filename}"
			def url = getNativeURL(path, filename)
			//TODO remove when Mojang launcher is updated
			if (!classifiers && art.classifier != null) { 
				// Mojang launcher doesn't currently support classifiers, so... move it to part of the version, and force the extension to 'jar'
				// However, keep the path normal so that our mirror system works.
				art.version = "${art.version}-${art.classifier}"
				art.classifier = null
				art.extension = 'jar'
				path = "${art.group.replace('.', '/')}/${art.name}/${art.version}/${art.name}-${art.version}.jar"
			}
			ret[key] = [
				name: "${art.group}:${art.name}:${art.version}" + (art.classifier == null ? '' : ":${art.classifier}") + (art.extension == 'jar' ? '' : "@${art.extension}"),
				downloads: [
					artifact: [
						path: path,
						url: url,
						sha1: sha1(art.file as File),
						size: art.file.length()
					]
				]
			]
		}
		return ret
	}

	/**
	 * The natives artifacts, as launcher-JSON library entries.
	 *
	 * <p>Read straight off the resolved configuration: every artifact already carries its module, version and
	 * classifier. An earlier version reconstructed those by matching jar file names against separate module and
	 * architecture lists, which made the order of those lists load-bearing ('lwjgl' is a substring of every module
	 * name). A resolved artifact whose classifier has no rule is a packaging bug, not a file to skip: the launcher
	 * would install everything except that one jar, and the failure would land on a player.
	 */
	static def getLWJGLNatives(nativeConfig) {
		def ret = [:]
		nativeConfig.resolvedConfiguration.resolvedArtifacts.each { art ->
			def classifier = art.classifier
			if (classifier == null || !NATIVE_CLASSIFIERS.containsKey(classifier))
				throw new GradleException("${art.moduleVersion.id.group}:${art.name}:${art.moduleVersion.id.version}"
						+ " has no launcher rule for natives classifier '${classifier}'")
			def group = art.moduleVersion.id.group
			def name = art.moduleVersion.id.name
			def version = art.moduleVersion.id.version
			def extension = art.extension
			def path = "${group.replace('.', '/')}/${name}/${version}/${name}-${version}-${classifier}.${extension}"
			def url = getNativeURL(path, "${name}-${version}-${classifier}.${extension}")
			ret[(group + ":" + name + ":" + classifier).toString()] = [
					name     : "${group}:${name}:${version}:${classifier}" + (extension == 'jar' ? '' : "@${extension}"),
					downloads: [
							artifact: [
									path : path,
									url  : url,
									sha1 : sha1(art.file as File),
									size : art.file.length()
							]
					],
					rules    : nativeRules(classifier)
			]
		}
		return ret
	}

	/** Aliyun's mirror of Maven Central - where players download central artifacts from. */
	static final String CENTRAL_MAVEN_URL = 'https://maven.aliyun.com/repository/central/'

	/** Mojang's own library host, the only place a handful of vanilla artifacts exist. */
	static final String MOJANG_MAVEN_URL = 'https://libraries.minecraft.net/'

	/**
	 * The mirror behind {@code repo.cleanroommc.com} (Aliyun-accelerated), hit directly so launchers do not have to
	 * follow the 307 that {@code repo.cleanroommc.com} answers with. It carries forge, mcp_config, cleanroom's own
	 * artifacts and {@code maven.outlands.top}, i.e. everything that is neither central nor Mojang's.
	 */
	static final String MIRROR_MAVEN_URL = 'https://nexus.jsdu.cn/repository/'

	/**
	 * Where the launcher-facing JSON (version.json, install_profile.json, the MMC pack) points players at, in
	 * probing order: central -> Aliyun, Mojang's -> its own host, everything else -> the mirror. The three hosts
	 * after the mirror are only reached for artifacts the mirror does not carry, so a broken mirror degrades into
	 * the previous behaviour instead of breaking the pack.
	 */
	static final List<String> LAUNCHER_REPOSITORIES = [
			CENTRAL_MAVEN_URL,
			MOJANG_MAVEN_URL,
			MIRROR_MAVEN_URL,
			'https://maven.outlands.top/releases/',
			'https://maven.minecraftforge.net/',
			'https://repo.cleanroommc.com/releases/',
			'https://maven.arcseekers.com/releases/',
	]

	/** path -> the base url that answered for it, so one build probes each artifact once. */
	private static final Map<String, String> nativeUrls = [:]

	static def getNativeURL(path, filename) {
		String known = nativeUrls.get(path)
		if (known != null) return known + path
		for (String base : LAUNCHER_REPOSITORIES) {
			if (checkExists(base + path)) {
				nativeUrls.put(path, base)
				return base + path
			}
		}
		throw new GradleException("Can't find " + filename + " from defined repositories.\n"
				+ "Please check and make sure all repositories are setup properly.\n"
				+ "At: top.outlands.gradle.helpers.tasks.Util#getNativeURL(String, String)\n"
				+ "Current defined repositories: (Sort by order)\n"
				+ String.join("\n" , LAUNCHER_REPOSITORIES) as String)
	}

	/**
	 * Every natives classifier we ship, and how a launcher is told to install it.
	 *
	 * <p><b>Why {@code os.name} is one of three vanilla names.</b> The official launcher deserialises {@code os.name}
	 * into {@code com.mojang.launcher.OperatingSystem} through {@code LowerCaseEnumTypeAdapterFactory}, and a name it
	 * does not know becomes {@code null} - which makes the restriction skip its own check, i.e. the rule matches on
	 * <em>every</em> platform. A name such as {@code osx-arm64} is therefore not "unrecognised", it is a wildcard: that
	 * was measured against the launcher's own classes, and it is why the architecture moved to {@code os.arch}.
	 *
	 * <p><b>Why {@code os.arch} is spelled the way it is.</b> The launcher compiles it as a regular expression and
	 * matches the <em>raw</em> {@code System.getProperty("os.arch")}: {@code aarch64}, {@code arm}, {@code amd64},
	 * {@code x86_64}, {@code x86}. {@code arm64} (the MultiMC spelling) never matches there, and {@code x86_64} is
	 * {@code amd64} on Linux, so the x64 baseline deliberately carries no architecture at all.
	 *
	 * <p>Prism and every launcher that compares {@code os.name} only ignore {@code arch}, so an arm entry reads as "this
	 * OS" to them: they install one jar more per OS. That is harmless - the jar carries only its own
	 * {@code <os>/<arch>/} tree and LWJGL picks by platform at load time - and it is the price of never leaving an arm
	 * machine without natives. The one name outside the enum is {@code freebsd}: the official launcher reports
	 * {@code UNKNOWN} there, so that rule leaks to everyone, and it is still the only way Prism-on-FreeBSD gets natives.
	 */
	static final Map<String, Map<String, String>> NATIVE_CLASSIFIERS = [
			'natives-linux'        : [name: 'linux'],
			'natives-linux-arm64'  : [name: 'linux', arch: 'aarch64'],
			'natives-macos'        : [name: 'osx'],
			'natives-macos-arm64'  : [name: 'osx'],
			'natives-windows'      : [name: 'windows'],
			'natives-windows-arm64': [name: 'windows', arch: 'aarch64'],
			'natives-freebsd'      : [name: 'freebsd'],
	]

	/** The launcher rule for one classifier: a single allow, never a disallow (see {@link #NATIVE_CLASSIFIERS}). */
	static List nativeRules(String classifier) {
		def spec = NATIVE_CLASSIFIERS.get(classifier)
		if (spec == null)
			throw new IllegalArgumentException("unknown natives classifier: ${classifier}")
		def restriction = [name: spec.get('name')]
		if (spec.get('arch') != null)
			restriction.put('arch', spec.get('arch'))
		return [[action: 'allow', os: restriction]]
	}

	/**
	 * The natives classifier for the machine this build runs on, used by the dev run and the test classpath.
	 *
	 * <p>Anything not in this table fails the build by name instead of falling back to {@code natives-linux}: an
	 * unsupported architecture used to load the x86_64 {@code .so} and fail much later, in {@code System.load}.
	 */
	static final Map<String, String> MACHINE_CLASSIFIERS = [
			'linux/x64'    : 'natives-linux',
			'linux/arm64'  : 'natives-linux-arm64',
			'osx/x64'      : 'natives-macos',
			'osx/arm64'    : 'natives-macos-arm64',
			'windows/x64'  : 'natives-windows',
			'windows/arm64': 'natives-windows-arm64',
			'freebsd/x64'  : 'natives-freebsd',
	]

	static def getCurrentArch() {
		def osName = System.getProperty("os.name").toLowerCase()
		def rawArch = System.getProperty("os.arch")
		def system
		if (osName.contains("linux")) system = "linux"
		else if (osName.contains("win")) system = "windows"
		else if (osName.contains("mac") || osName.contains("darwin")) system = "osx"
		else if (osName.contains("freebsd")) system = "freebsd"

		// Normalised the way MultiMC does it, so 'amd64' on Linux and 'x86_64' on macOS land on the same key.
		def arch
		switch (rawArch) {
			case "amd64": case "x86_64": case "x64": arch = "x64"; break
			case "aarch64": case "arm64": arch = "arm64"; break
			case "x86": case "i386": case "i686": arch = "x86"; break
			case "arm": case "armhf": case "arm32": arch = "arm32"; break
			default: arch = rawArch
		}

		def classifier = MACHINE_CLASSIFIERS.get((system + "/" + arch).toString())
		if (classifier == null)
			throw new GradleException("no LWJGL natives for os.name='${System.getProperty("os.name")}'" +
					" os.arch='${rawArch}' (known: ${MACHINE_CLASSIFIERS.keySet().sort().join(', ')})")
		return classifier
	}

	static def iso8601Now() {
		new Date().iso8601()
	}

	static def sha1(File file) {
		MessageDigest md = MessageDigest.getInstance('SHA-1')
		file.eachByte 4096, {byte[] bytes, int size ->
			md.update(bytes, 0, size)
		}
		return md.digest().collect {String.format "%02x", it}.join()
	}

	private static def artifactTree(project, artifact) {
		if (!project.ext.has('tree_resolver'))
			project.ext.tree_resolver = 1
		def cfg = project.configurations.create('tree_resolver_' + project.ext.tree_resolver++)
		def dep = project.dependencies.create(artifact)
		cfg.dependencies.add(dep)
		def files = cfg.resolve()
		return getArtifacts(cfg, true)
	}
	
	private static boolean checkExists(String url) {
		// The mirror occasionally drops a connection instead of answering (SSL EOF), which would silently move the
		// artifact to a fallback host - so a transport failure is retried once. A 404 is an answer and not retried.
		for (int attempt = 0; attempt < 2; attempt++) {
			try {
				def code = new URI(url).toURL().openConnection().with {
					requestMethod = 'HEAD'
					connectTimeout = 15_000
					readTimeout = 30_000
					connect()
					responseCode
				}
				return code == 200
			} catch (Exception e) {
				if (e.toString().contains('unable to find valid certification path to requested target'))
					throw new RuntimeException('Failed to connect to ' + url + ': Missing certificate root authority, try updating java')
			}
		}
		// Not there, as far as this host is concerned: let the caller try the next one.
		return false
	}

}