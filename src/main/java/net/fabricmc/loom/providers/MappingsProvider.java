/*
 * Copyright 2019, 2020 Chocohead
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package net.fabricmc.loom.providers;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;
import java.util.zip.GZIPInputStream;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;

import cuchaz.enigma.command.MapSpecializedMethodsCommand;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;

import org.gradle.api.Project;
import org.gradle.api.logging.Logger;

import net.fabricmc.loom.LoomGradleExtension;
import net.fabricmc.loom.LoomGradleExtension.JarMergeOrder;
import net.fabricmc.loom.dependencies.DependencyProvider;
import net.fabricmc.loom.dependencies.LogicalDependencyProvider;
import net.fabricmc.loom.providers.StackedMappingsProvider.MappingFile;
import net.fabricmc.loom.providers.StackedMappingsProvider.MappingFile.MappingType;
import net.fabricmc.loom.providers.mappings.EnigmaReader;
import net.fabricmc.loom.providers.mappings.MappingBlob;
import net.fabricmc.loom.providers.mappings.MappingBlob.InvertionTarget;
import net.fabricmc.loom.providers.mappings.MappingBlob.Mapping;
import net.fabricmc.loom.providers.mappings.MappingBlob.Mapping.Field;
import net.fabricmc.loom.providers.mappings.MappingBlob.Mapping.Method;
import net.fabricmc.loom.providers.mappings.MappingSplat;
import net.fabricmc.loom.providers.mappings.MappingSplat.CombinedMapping;
import net.fabricmc.loom.providers.mappings.MappingSplat.CombinedMapping.CombinedField;
import net.fabricmc.loom.providers.mappings.MappingSplat.CombinedMapping.CombinedMethod;
import net.fabricmc.loom.providers.mappings.TinyDuplicator;
import net.fabricmc.loom.providers.mappings.TinyReader;
import net.fabricmc.loom.providers.mappings.TinyV2toV1;
import net.fabricmc.loom.providers.mappings.TinyWriter;
import net.fabricmc.loom.util.Constants;
import net.fabricmc.loom.util.TinyRemapperMappingsHelper;
import net.fabricmc.mappings.Mappings;
import net.fabricmc.stitch.commands.CommandProposeFieldNames;
import net.fabricmc.tinyremapper.IMappingProvider;

public class MappingsProvider extends LogicalDependencyProvider {
	public interface MappingFactory {//IOException throwing BiFunction<String, String, IMappingProvider>
		IMappingProvider create(String fromMapping, String toMapping) throws IOException;
	}
	public MappingFactory mcRemappingFactory;

	static final String INTERMEDIARY = "net.fabricmc.intermediary";
	private final List<MappingFile> mappingFiles = new ArrayList<>();

	public String mappingsName;
	public String minecraftVersion;
	public String mappingsVersion;

	public File MAPPINGS_DIR;
	public File MAPPINGS_MIXIN_EXPORT;

	private Path stackHistory;
	private boolean knownStack;
	private File intermediaryNames;
	// The mappings that gradle gives us
	private File MAPPINGS_TINY_BASE;
	// The mappings we use in practice
	public File MAPPINGS_TINY;
	private Path parameterNames, decompileComments;

	public Mappings getMappings() throws IOException {
		return MappingsCache.INSTANCE.get(MAPPINGS_TINY.toPath());
	}

	public Path getDecompileMappings() {
		return decompileComments;
	}

	@Override
	public Set<Class<? extends DependencyProvider>> getDependencies() {
		return ImmutableSet.of(StackedMappingsProvider.class, MinecraftProvider.class);
	}

	void stackMappings(MappingFile mappings) {
		mappingFiles.add(mappings);
	}

	@Override
	public void provide(Project project, LoomGradleExtension extension, Consumer<Runnable> postPopulationScheduler) throws Exception {
		MinecraftProvider minecraftProvider = getProvider(MinecraftProvider.class);

		initFiles(extension, project.getLogger(), minecraftProvider);

		if (!MAPPINGS_TINY_BASE.exists() || !MAPPINGS_TINY.exists()) {
			if (!MAPPINGS_DIR.exists()) {
				MAPPINGS_DIR.mkdir();
			}

			free: if (!MAPPINGS_TINY_BASE.exists()) {
				Map<String, List<MappingFile>> versionToMappings = new HashMap<>();

				for (ListIterator<MappingFile> it = mappingFiles.listIterator(); it.hasNext();) {
					MappingFile file = it.next();

					if (file.type.needsEnlightening()) {
						it.set(file = file.enlighten()); //Need to work out what the type of the ambiguous mapping files are
					}

					versionToMappings.computeIfAbsent(file.minecraftVersion, k -> new ArrayList<>()).add(file);
				}

				for (List<MappingFile> mappings : versionToMappings.values()) {
					mappings.sort((fileA, fileB) -> {
						if (fileA.type == fileB.type) return 0;

						switch (fileA.type) {//Sort by ease of ability to extract headers
						case TinyV1:
							return -1;

						case TinyV2:
							return fileB.type == MappingType.TinyV1 ? 1 : -1;

						case TinyGz:
							return fileB.type == MappingType.Enigma ? -1 : 1;

						case Enigma:
							return 1;

						default:
						case Tiny:
							throw new IllegalArgumentException("Unexpected mapping types to compare: " + fileA.type + " and " + fileB.type);
						}
					});
				}

				//Need to see if any of the mapping files have Intermediaries for the Minecraft version they're going to be running on
				Optional<MappingFile> interProvider = searchForIntermediaries(versionToMappings.getOrDefault(minecraftVersion, Collections.emptyList()), minecraftProvider.getNeededHeaders());

				MappingBlob intermediaries;
				if (interProvider.isPresent()) {
					MappingFile mappings = interProvider.get();

					if (mappingFiles.size() == 1) {
						assert mappings == Iterables.getOnlyElement(mappingFiles);

						project.getLogger().lifecycle(":extracting " + mappings.origin.getName());
						switch (mappings.type) {
						case TinyV1:
							try (FileSystem fileSystem = FileSystems.newFileSystem(mappings.origin.toPath(), null)) {
								Files.copy(fileSystem.getPath("mappings/mappings.tiny"), MAPPINGS_TINY_BASE.toPath());
							}
							break free;

						case TinyGz:
							FileUtils.copyInputStreamToFile(new GZIPInputStream(new FileInputStream(mappings.origin)), MAPPINGS_TINY_BASE);
							break free;

						case TinyV2:
							TinyV2toV1.convert(mappings.origin.toPath(), MAPPINGS_TINY_BASE.toPath(), parameterNames, decompileComments);
							break free;

						case Enigma:
						case Tiny:
						default: //Shouldn't end up here if this is the only mapping file supplied
							throw new IllegalStateException("Unexpected mappings type " + mappings.type + " from " + mappings.origin);
						}
					}

					if (minecraftProvider.getMergeStrategy() != JarMergeOrder.FIRST) {
						throw new UnsupportedOperationException("Mapping stacking only currently supports immediately merged jars");
					}

					project.getLogger().lifecycle(":loading intermediaries " + mappings.origin.getName());
					switch (mappings.type) {
					case Tiny:
						assert false: "Unexpected mappings type " + mappings.type + " from " + mappings.origin;
					case TinyV1:
					case TinyV2:
						try (FileSystem fileSystem = FileSystems.newFileSystem(mappings.origin.toPath(), null)) {
							//Would be nice to extract this out but then the file system is closed before the mappings can be read
							TinyReader.readTiny(fileSystem.getPath("mappings/mappings.tiny"), "official", "intermediary", intermediaries = new MappingBlob());
						}
						break;

					case TinyGz:
						TinyReader.readTiny(mappings.origin.toPath(), "official", "intermediary", intermediaries = new MappingBlob());
						break;

					case Enigma:
					default: //Shouldn't end up here given Enigma won't be supplying the intermediaries
						throw new IllegalStateException("Unexpected mappings type " + mappings.type + " from " + mappings.origin);
					}
				} else {
					if (!intermediaryNames.exists()) {//Grab intermediary mappings from Github
						project.getLogger().lifecycle(":downloading intermediaries " + intermediaryNames.getName());
						FileUtils.copyURLToFile(new URL(SpecialCases.intermediaries(minecraftVersion)), intermediaryNames);
					} else {
						project.getLogger().lifecycle(":loading intermediaries " + intermediaryNames.getName());
					}

					if (mappingFiles.isEmpty()) {
						TinyDuplicator.duplicateV1Column(intermediaryNames.toPath(), MAPPINGS_TINY_BASE.toPath(), "intermediary", "named");
						break free;
					}

					if (minecraftProvider.getMergeStrategy() != JarMergeOrder.FIRST) {
						throw new UnsupportedOperationException("Mapping stacking only currently supports immediately merged jars");
					}

					TinyReader.readTiny(intermediaryNames.toPath(), "official", "intermediary", intermediaries = new MappingBlob());
				}

				MappingBlob inversion = intermediaries.invert(InvertionTarget.MEMBERS);
				MappingBlob mappings = new MappingBlob();
				Map<String, MappingBlob> versionToIntermediaries = new HashMap<>();

				for (MappingFile mapping : mappingFiles) {
					project.getLogger().lifecycle(":loading " + mapping.origin.getName());

					MappingBlob gains = new MappingBlob();
					boolean nativeNames = false;

					switch (mapping.type) {
					case Enigma: {
						EnigmaReader.readEnigma(mapping.origin.toPath(), gains);

						if (gains.stream().parallel().noneMatch(classMapping -> classMapping.from.startsWith("net/minecraft/class_"))) {
							nativeNames = true;
						} else {
							assert gains.stream().parallel().filter(classMapping -> classMapping.to() != null).allMatch(classMapping -> classMapping.from.startsWith("net/minecraft/class_") || classMapping.from.matches("com\\/mojang\\/.+\\$class_\\d+")):
								gains.stream().filter(classMapping -> classMapping.to() != null && !classMapping.from.startsWith("net/minecraft/class_") && !classMapping.from.matches("com\\/mojang\\/.+\\$class_\\d+")).map(classMapping -> classMapping.from).collect(Collectors.joining(", ", "Found unexpected initial mapping classes: [", "]"));
							assert gains.streamMethods().parallel().filter(method -> method.name() != null).allMatch(method -> method.fromName.startsWith("method_") || method.fromName.equals(method.name())):
								gains.streamMethods().filter(method -> method.name() != null && !method.fromName.startsWith("method_")).map(method -> method.fromName + method.fromDesc).collect(Collectors.joining(", ", "Found unexpected method mappings: ", "]"));
							assert gains.streamFields().parallel().filter(field -> field.name() != null).allMatch(field -> field.fromName.startsWith("field_")):
								gains.streamFields().filter(field -> field.name() != null && !field.fromName.startsWith("field_")).map(field -> field.fromName).collect(Collectors.joining(", ", "Found unexpected field mappings: ", "]"));
						}

						Path contextJar;
						if (minecraftVersion.equals(mapping.minecraftVersion)) {
							if (nativeNames) {
								contextJar = minecraftProvider.getMergedJar().toPath();
							} else {
								contextJar = SnappyRemapper.remapCurrentJar(project, extension, minecraftProvider, interProvider.map(mappingFile -> mappingFile.origin.toPath()));
							}
						} else {
							if (nativeNames) {
								contextJar = SnappyRemapper.makeMergedJar(project, extension, mapping.minecraftVersion, Optional.empty(), JarMergeOrder.INDIFFERENT).getMergedJar().toPath();
							} else {
								contextJar = SnappyRemapper.makeInterJar(project, extension, mapping.minecraftVersion, //See if we've actually got the old Intermediaries per chance too
										searchForIntermediaries(versionToMappings.getOrDefault(mapping.minecraftVersion, Collections.emptyList())).map(mappingFile -> mappingFile.origin.toPath()));
							}
						}

						String from = nativeNames ? "official" : "intermediary";
						Path specialisedMappings = MAPPINGS_DIR.toPath().resolve(FilenameUtils.removeExtension(mapping.origin.getName()) + "-specialised.jar");
						try (FileSystem fs = FileSystems.newFileSystem(new URI("jar:" + specialisedMappings.toUri()), Collections.singletonMap("create", "true"))) {
							Path destination = fs.getPath("mappings/mappings.tiny");

							Files.createDirectories(destination.getParent());
							MapSpecializedMethodsCommand.run(contextJar, "enigma", mapping.origin.toPath(), "tinyv2:" + from + ":named", destination);
						} catch (URISyntaxException e) {
							throw new IllegalStateException("Cannot convert jar path to URI?", e);
						} catch (IOException e) {
							throw new UncheckedIOException("Error creating mappings jar", e);
						}

						mapping = new MappingFile(specialisedMappings.toFile(), mapping.name, mapping.version, mapping.minecraftVersion, MappingType.TinyV2, ImmutableList.of(from, "named"));
					}

					case TinyV1:
					case TinyV2: {
						String origin;
						if (mapping.getNamespaces().contains("intermediary")) {
							origin = "intermediary";
						} else {
							nativeNames = true;
							origin = "official";
						}
						assert mapping.getNamespaces().contains("named");

						try (FileSystem fileSystem = FileSystems.newFileSystem(mapping.origin.toPath(), null)) {
							TinyReader.readTiny(fileSystem.getPath("mappings/mappings.tiny"), origin, "named", gains);

							if (mapping.type == MappingType.TinyV2) {
								UnaryOperator<String> classRemapper;
								if (!origin.equals(mapping.getNamespaces().get(0))) {
									MappingBlob nativeToOrigin = new MappingBlob();
									TinyReader.readTiny(fileSystem.getPath("mappings/mappings.tiny"), mapping.getNamespaces().get(0), origin, nativeToOrigin);
									classRemapper = name -> {
										String remap = nativeToOrigin.tryMapName(name);
										return remap != null ? remap : name;
									};
								} else {
									classRemapper = null; //Origin column is the main column, descriptors won't need to be renamed
								}
								TinyReader.readComments(fileSystem.getPath("mappings/mappings.tiny"), origin, classRemapper, gains);
							}
						}
						break;
					}

					case TinyGz: {
						Collection<String> namespaces = TinyReader.readHeaders(mapping.origin.toPath());

						String origin;
						if (namespaces.contains("intermediary")) {
							origin = "intermediary";
						} else {
							nativeNames = true;
							origin = "official";
						}
						assert namespaces.contains("named");

						TinyReader.readTiny(mapping.origin.toPath(), origin, "named", gains);
						break;
					}

					case Tiny: //Should have already enlightened this by now
						throw new IllegalStateException("Unexpected mappings type " + mapping.type + " from " + mapping.origin);
					}

					if (nativeNames) {
						MappingBlob renamer;
						if (!minecraftVersion.equals(mapping.minecraftVersion)) {
							renamer = versionToIntermediaries.computeIfAbsent(mapping.minecraftVersion, version -> {
								Path intermediaryNames = searchForIntermediaries(versionToMappings.getOrDefault(version, Collections.emptyList()))
										.map(mappingFile -> mappingFile.origin.toPath()).orElseGet(() -> SnappyRemapper.getIntermediaries(extension, version));

								MappingBlob inters = new MappingBlob();
								try {
									TinyReader.readTiny(intermediaryNames, "official", "intermediary", inters);
								} catch (IOException e) {
									throw new UncheckedIOException("Error reading Intermediary mappings for " + version, e);
								}
								return inters;
							});
						} else {
							renamer = intermediaries;
						}

						gains = gains.rename(renamer);
					}

					for (Mapping classMapping : gains) {
						//If the name has been lost since it was named there's no point including it
						if (inversion.tryMapName(classMapping.from) == null) continue;
						Mapping interMapping = inversion.get(classMapping.from);

						Mapping existingClass = mappings.get(classMapping.from);
						if (existingClass.to() == null && !classMapping.from.equals(classMapping.to())) {
							mappings.acceptClass(classMapping.from, classMapping.to());
						}
						assert interMapping.from.equals(existingClass.from);

						if (!existingClass.comment().isPresent()) {
							classMapping.comment().ifPresent(comment -> {
								mappings.acceptClassComment(classMapping.from, comment);
							});
						}

						for (Method method : classMapping.methods()) {
							if (!interMapping.hasMethod(method) && method.fromName.charAt(0) != '<') continue;

							Method existingMethod = existingClass.method(method);
							if (existingMethod.name() == null && !existingMethod.fromName.equals(method.name())) {
								mappings.acceptMethod(classMapping.from, method.fromName, method.fromDesc, existingClass.to(), method.name(), method.desc());
							}

							if (!existingMethod.comment().isPresent()) {
								method.comment().ifPresent(comment -> {
									mappings.acceptMethodComment(classMapping.from, method.fromName, method.fromDesc, comment);
								});
							}

							if (method.hasArgs()) {
								method.iterateArgs((arg, index) -> {
									if (existingMethod.arg(index) == null) {
										mappings.acceptMethodArg(classMapping.from, method.fromName, method.fromDesc, index, arg);
									}
								});
								method.iterateArgComments((comment, index) -> {
									if (!existingMethod.argComment(index).isPresent()) {
										mappings.acceptMethodArgComment(classMapping.from, method.fromName, method.fromDesc, index, comment);
									}
								});
							}
						}

						for (Field field : classMapping.fields()) {
							if (!interMapping.hasField(field)) continue;

							Field existingField = existingClass.field(field);
							if (existingField.name() == null && !existingField.fromName.equals(field.name())) {
								mappings.acceptField(classMapping.from, field.fromName, field.fromDesc, existingClass.to(), field.name(), field.desc());
							}

							if (!existingField.comment().isPresent()) {
								field.comment().ifPresent(comment -> {
									mappings.acceptMethodComment(classMapping.from, field.fromName, field.fromDesc, comment);
								});
							}
						}
					}
				}

				project.getLogger().lifecycle(":combining mappings");
				MappingSplat combined = new MappingSplat(mappings.rename(inversion), intermediaries);

				project.getLogger().lifecycle(":writing " + MAPPINGS_TINY_BASE.getName());
				try (TinyWriter writer = new TinyWriter(MAPPINGS_TINY_BASE.toPath(), "official", "named", "intermediary")) {
					for (CombinedMapping mapping : combined) {
						String notch = mapping.from;
						if (mapping.hasNameChange()) writer.acceptClass(notch, mapping.to, mapping.fallback);

						for (CombinedMethod method : mapping.methodsWithNames()) {
							writer.acceptMethod(notch, method.fromDesc, method.from, method.to, method.fallback);
						}

						for (CombinedField field : mapping.fieldsWithNames()) {
							writer.acceptField(notch, field.fromDesc, field.from, field.to, field.fallback);
						}
					}
				}

				if (combined.hasArgNames()) {
					project.getLogger().lifecycle(":writing " + parameterNames.getFileName());
					try (BufferedWriter writer = Files.newBufferedWriter(parameterNames)) {
						for (CombinedMapping mapping : combined) {
							for (CombinedMethod method : mapping.methodsWithArgs()) {
								if (!method.hasArgNames()) continue; //Just comments for the arguments

								writer.write(mapping.to);
								writer.write('/');
								writer.write(method.from);
								writer.write(method.fromDesc);
								writer.newLine();

								method.<IOException>iterateArgs((index, arg) -> {
									assert arg != null; //Should be skipping nulls

									writer.write('\t');
									writer.write(Integer.toString(index));
									writer.write(':');
									writer.write(' ');
									writer.write(arg);
									writer.newLine();
								});
							}
						}
					}
				}

				if (combined.hasComments()) {
					project.getLogger().lifecycle(":writing " + decompileComments.getFileName());
					try (BufferedWriter writer = Files.newBufferedWriter(decompileComments)) {
						TinyV2toV1.writeComments(writer, combined);
					}
				}

				if (MAPPINGS_TINY.exists()) {
					MAPPINGS_TINY.delete();
				}

				//If we've successfully joined all the mappings together, save the stack
				if (!knownStack && mappingFiles.size() > 1) writeStackHistory(mappingsVersion);
			}

			assert MAPPINGS_TINY_BASE.exists();
			assert !MAPPINGS_TINY.exists();

			project.getLogger().lifecycle(":populating field names");
			String namespace;
			switch (minecraftProvider.getMergeStrategy()) {
			case FIRST:
				namespace = "official";
				break;

			case LAST:
				namespace = "intermediary";
				break;

			case CLIENT_ONLY:
				namespace = "client";
				break;

			case SERVER_ONLY:
				namespace = "server";
				break;

			case INDIFFERENT:
			default:
				throw new IllegalStateException("Unexpected jar merge strategy " + minecraftProvider.getMergeStrategy());
			}
			CommandProposeFieldNames.run(minecraftProvider.getMergedJar(), MAPPINGS_TINY_BASE, MAPPINGS_TINY, namespace, "named");
		}

		if (Files.exists(parameterNames)) {
			//Merge the tiny mappings with parameter names
			Map<String, String[]> lines = new HashMap<>();

			try (BufferedReader reader = Files.newBufferedReader(parameterNames)) {
				for (String line = reader.readLine(), current = null; line != null; line = reader.readLine()) {
					if (current == null || line.charAt(0) != '\t') {
						current = line;
					} else {
						int split = line.indexOf(':'); //\tno: name
						int number = Integer.parseInt(line.substring(1, split));
						String name = line.substring(split + 2);

						String[] lineSet = lines.get(current);
						if (lineSet == null) {
							//The args are written backwards so the biggest index is first
							lines.put(current, lineSet = new String[number + 1]);
						}
						lineSet[number] = name;
					}
				}
			}

			mcRemappingFactory = (fromM, toM) -> new IMappingProvider() {
				private final IMappingProvider normal = TinyRemapperMappingsHelper.create(getMappings(), fromM, toM, false);

				@Override
				public void load(Map<String, String> classMap, Map<String, String> fieldMap, Map<String, String> methodMap, Map<String, String[]> localMap) {
					load(classMap, fieldMap, methodMap);
					if ("intermediary".equals(fromM)) {
						localMap.putAll(lines);
					}
				}

				@Override
				public void load(Map<String, String> classMap, Map<String, String> fieldMap, Map<String, String> methodMap) {
					normal.load(classMap, fieldMap, methodMap);
				}
			};
		} else {
			mcRemappingFactory = (fromM, toM) -> TinyRemapperMappingsHelper.create(getMappings(), fromM, toM, false);
		}

		File mappingJar;
		if (mappingFiles.size() == 1 && Iterables.getOnlyElement(mappingFiles).enlighten().type == MappingType.TinyV1) {
			mappingJar = Iterables.getOnlyElement(mappingFiles).origin;
			if (MAPPINGS_TINY.lastModified() < mappingJar.lastModified()) MAPPINGS_TINY.setLastModified(mappingJar.lastModified() - 1);
		} else {
			mappingJar = new File(MAPPINGS_DIR, FilenameUtils.removeExtension(MAPPINGS_TINY.getName()) + ".jar");

			if (!mappingJar.exists() || mappingJar.lastModified() < MAPPINGS_TINY.lastModified()) {
				try (FileSystem fs = FileSystems.newFileSystem(new URI("jar:" + mappingJar.toURI()), Collections.singletonMap("create", "true"))) {
					Path destination = fs.getPath("mappings/mappings.tiny");

					Files.createDirectories(destination.getParent());
					Files.copy(MAPPINGS_TINY.toPath(), destination, StandardCopyOption.REPLACE_EXISTING);
				} catch (URISyntaxException e) {
					throw new IllegalStateException("Cannot convert jar path to URI?", e);
				} catch (IOException e) {
					throw new UncheckedIOException("Error creating mappings jar", e);
				}
			}
		}

		assert mappingJar.exists() && mappingJar.lastModified() >= MAPPINGS_TINY.lastModified();
		addDependency(mappingJar, project, Constants.MAPPINGS);
	}

	@Deprecated
	private static Optional<MappingFile> searchForIntermediaries(List<MappingFile> mappings) {
		return searchForIntermediaries(mappings, ImmutableSet.of("official", "intermediary"));
	}

	private static Optional<MappingFile> searchForIntermediaries(List<MappingFile> mappings, Set<String> interHeaders) {
		return mappings.stream().filter(file -> {
			try {
				List<String> headers;
				switch (file.type) {
				case Enigma: //Never will (unless it goes Notch <=> Intermediary which is pointless to be in Enigma's format)
					return false;

				case TinyV1:
				case TinyV2:
					headers = file.getNamespaces();
					break;

				case TinyGz:
					headers = TinyReader.readHeaders(file.origin.toPath());
					break;

				default:
				case Tiny:
					throw new IllegalArgumentException("Unexpected mapping types to read: " + file.type);
				}

				return headers.containsAll(interHeaders);
			} catch (IOException e) {
				throw new UncheckedIOException("Error reading mapping file from " + file.origin, e);
			}
		}).findFirst();
	}

	private String readStackHistory() {
		if (Files.notExists(stackHistory)) {
			return "1";
		} else {
			List<String> expected = mappingFiles.stream().map(mappings -> mappings.name + '-' + mappings.version + ' ' + mappings.minecraftVersion).collect(Collectors.toList());
			assert !expected.isEmpty();

			try (BufferedReader reader = Files.newBufferedReader(stackHistory)) {
				String currentVersion = reader.readLine();
				String newestVersion = currentVersion;
				int currentPosition = 0;
				for (String line = reader.readLine(); line != null; line = reader.readLine()) {
					assert !line.isEmpty();

					if (line.charAt(0) != '\t') {
						if (currentPosition == expected.size()) {
							assert currentVersion != null;
							knownStack = true;
							return currentVersion;
						}

						currentVersion = line;
						currentPosition = 0;
					} else if (currentVersion != null) {
						assert line.charAt(0) == '\t';

						if (currentPosition >= expected.size() || !line.regionMatches(1, expected.get(currentPosition), 0, line.length() - 1)) {
							currentVersion = null;
						} else {
							currentPosition++;
						}
					}
				}

				return Integer.toString(Integer.parseUnsignedInt(newestVersion) + 1);
			} catch (IOException e) {
				throw new UncheckedIOException("Error reading stack history file at " + stackHistory, e);
			}
		}
	}

	private void writeStackHistory(String version) {
		assert !knownStack; //No need to go through all this if it's already in the history

		StringBuilder addition = new StringBuilder(version).append('\n');

		for (MappingFile mapping : mappingFiles) {
			addition.append('\t').append(mapping.name).append('-').append(mapping.version).append(' ').append(mapping.minecraftVersion).append('\n');
		}

		byte data[] = addition.toString().getBytes(StandardCharsets.UTF_8);
		ByteBuffer active;
		if (data.length >= 4096) {
			active = ByteBuffer.wrap(data);
		} else {
			active = ByteBuffer.allocate(4096);
			active.put(data);
			active.flip();
		}
		assert active.limit() == data.length;

		ByteBuffer passive = ByteBuffer.allocate(active.capacity());

		try (FileChannel channel = FileChannel.open(stackHistory, StandardOpenOption.READ, StandardOpenOption.WRITE, StandardOpenOption.CREATE)) {
			assert channel.position() == 0;

			if (passive.capacity() != active.capacity()) {//It's important the amount of the file read is the same as the amount written
				throw new IllegalArgumentException("Inconsistent capacities between buffers: " + passive.capacity() + " and " + active.capacity());
			}

			long offset = passive.capacity() - active.limit(); //Account for reading/writing not necessarily being at the same start/end positions
			assert offset >= 0;
			do {
				long position = channel.position();
				if (position > 0 && offset > 0) {
					channel.position(position + offset);
				}

				passive.clear(); //Ensure the buffer's limit == capacity, as well as position == 0

				int read;
				do {
					read = channel.read(passive);
				} while (read != -1 && passive.hasRemaining());
				//System.out.println("Read in \"" + new String(passive.array(), 0, passive.position()) + '"');
				passive.flip();

				channel.position(position);

				//System.out.println("Writing \"" + new String(active.array(), active.position(), active.remaining()) + '"');
				while (active.hasRemaining()) {
					channel.write(active);
				}

				ByteBuffer swap = passive;
				passive = active;
				active = swap;
			} while (active.limit() >= active.capacity());

			while (active.hasRemaining()) {
				//System.out.println("Writing final \"" + new String(active.array(), active.position(), active.remaining()) + '"');
				channel.write(active);
			}
		} catch (IOException e) {
			throw new UncheckedIOException("Error writing stack history file to " + stackHistory, e);
		}
	}

	private void initFiles(LoomGradleExtension extension, Logger logger, MinecraftProvider minecraftProvider) {
		MAPPINGS_DIR = new File(extension.getUserCache(), "mappings/" + minecraftProvider.minecraftVersion);

		switch (mappingFiles.size()) {
		case 0:
			logger.lifecycle(":setting up mappings (" + INTERMEDIARY + ' ' + minecraftProvider.minecraftVersion + ')');
			mappingsName = INTERMEDIARY;
			mappingsVersion = minecraftVersion = minecraftProvider.minecraftVersion;
			break;

		case 1: {
			MappingFile mappings = Iterables.getOnlyElement(mappingFiles);
			logger.lifecycle(":setting up mappings (" + mappings.name + ' ' + mappings.version + '@' + mappings.minecraftVersion + ')');
			mappingsName = mappings.name;
			mappingsVersion = mappings.version;
			minecraftVersion = mappings.minecraftVersion;
			break;
		}

		default: {
			logger.lifecycle(":setting up mappings (" + mappingFiles.size() + " files in stack)");
			stackHistory = new File(MAPPINGS_DIR, "stack.history").toPath();

			mappingsName = "stack";
			mappingsVersion = readStackHistory();
			//The stack could be made up of multiple Minecraft versions, so we'll just use the version the stack will run on
			minecraftVersion = minecraftProvider.minecraftVersion;
			break;
		}
		}

		intermediaryNames = new File(MAPPINGS_DIR, INTERMEDIARY + "-intermediary.tiny");
		MAPPINGS_TINY_BASE = new File(MAPPINGS_DIR, mappingsName + "-tiny-" + minecraftVersion + '-' + mappingsVersion + "-base.tiny");
		MAPPINGS_TINY = new File(MAPPINGS_DIR, mappingsName + "-tiny-" + minecraftVersion + '-' + mappingsVersion + ".tiny");
		parameterNames = new File(MAPPINGS_DIR, mappingsName + "-params-" + minecraftVersion + '-' + mappingsVersion).toPath();
		decompileComments = parameterNames.resolveSibling(mappingsName + "-tiny-" + minecraftVersion + '-' + mappingsVersion + "-decomp.tiny");

		MAPPINGS_MIXIN_EXPORT = new File(extension.getProjectBuildCache(), "mixin-map-" + minecraftVersion + '-' + mappingsVersion + ".tiny");
	}

	public void clearFiles() {
		MAPPINGS_TINY.delete();
		MAPPINGS_TINY_BASE.delete();
		intermediaryNames.delete();
		try {
			Files.deleteIfExists(parameterNames);
			Files.deleteIfExists(decompileComments);
		} catch (IOException e) {
			e.printStackTrace(); //That's troublesome
		}
	}
}
