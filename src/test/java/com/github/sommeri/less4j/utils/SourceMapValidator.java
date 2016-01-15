package com.github.sommeri.less4j.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.io.IOUtils;
import javax.json.Json;
import javax.json.JsonObject;

import com.github.sommeri.less4j.LessCompiler.CompilationResult;
import com.github.sommeri.sourcemap.FilePosition;
import com.github.sommeri.sourcemap.SourceMapConsumerV3;
import com.github.sommeri.sourcemap.SourceMapConsumerV3.EntryVisitor;
import com.github.sommeri.sourcemap.SourceMapParseException;

public class SourceMapValidator {

  private static final String CALCULATED_SYMBOLS_PROPERTY = "calculatedNames";
  private static final String SYMBOLS_PROPERTY = "names";
  private static final String SOURCES_PROPERTY = "sources";
  private static final String SOURCES_CEONTENT_PROPERTY = "sourcesContent";
  private static final String CSS_FILE_PROPERTY = "file";
  private static final String SOURCE_ROOT_PROPERTY = "sourceRoot";
  protected static final String ALL = "*all*";

  private Map<String, MappedFile> mappedFiles = new HashMap<String, MappedFile>();
  private MappedFile cssFile;
  private Map<String, String> contents = new HashMap<String, String>();
  private String customRoot = null;

  public SourceMapValidator() {
  }

  public SourceMapValidator(String customRoot) {
    this.customRoot = customRoot;
  }

  public SourceMapValidator(Map<String, String> contents) {
    this.contents = contents;
  }

  public void validateSourceMap(CompilationResult compilationResult, File mapdataFile) {
    validateSourceMap(compilationResult, mapdataFile, null);
  }

  public void validateSourceMap(CompilationResult compilationResult, File mapdataFile, File cssFileLocation) {
    initializeGeneratedCss(compilationResult);
    SourceMapConsumerV3 sourceMap = parseGeneratedMap(compilationResult);

    Mapdata mapdata = checkAgainstMapdataFile(sourceMap, mapdataFile);
    loadMappedSourceFiles(sourceMap.getOriginalSources(), getSourceRoot(cssFileLocation), sourceMap.getOriginalSourcesContent());

    validateSymbolMappings(sourceMap, mapdata);
  }

  private void validateSymbolMappings(SourceMapConsumerV3 sourceMap, Mapdata mapdata) {
    MappingEntryValidation mappingEntryValidation = new MappingEntryValidation(mapdata);
    sourceMap.visitMappings(mappingEntryValidation);
  }

  private String getSourceRoot(File cssFileLocation) {
    if (customRoot!=null)
      return customRoot;
    
    if (cssFileLocation != null)
      return URIUtils.addPLatformSlashIfNeeded(cssFileLocation.getParentFile().getAbsolutePath());

    return "";
  }

  private SourceMapConsumerV3 parseGeneratedMap(CompilationResult compilationResult) {
    try {
      SourceMapConsumerV3 sourceMap = new SourceMapConsumerV3();
      sourceMap.parse(compilationResult.getSourceMap());
      return sourceMap;
    } catch (SourceMapParseException e) {
      throw new RuntimeException(e);
    }
  }

  private Mapdata checkAgainstMapdataFile(SourceMapConsumerV3 sourceMap, File mapdataFile) {
    Mapdata expectedMapdata = loadMapdata(mapdataFile);

    // validate mapdata file
    if (expectedMapdata.hasSources()) {
      CustomAssertions.assertEqualsAsSets(expectedMapdata.getSources(), sourceMap.getOriginalSources());
    }
    if (expectedMapdata.hasSourcesContent()) {
      CustomAssertions.assertEqualsAsSets(expectedMapdata.getSourcesContent(), sourceMap.getOriginalSourcesContent());
    }
    if (expectedMapdata.hasSymbols()) {
      CustomAssertions.assertEqualsAsSets(expectedMapdata.getSymbols(), allSymbols(sourceMap));
    }
    if (expectedMapdata.hasFile()) {
      assertEquals(expectedMapdata.getFile(), sourceMap.getFile());
    }
    if (expectedMapdata.hasSourceRoot()) {
      assertEquals(expectedMapdata.getSourceRoot(), sourceMap.getSourceRoot());
    }
    return expectedMapdata;
  }

  private Collection<String> allSymbols(SourceMapConsumerV3 sourceMap) {
    SymbolsCollector symbolsCollector = new SymbolsCollector();
    sourceMap.visitMappings(symbolsCollector);
    return symbolsCollector.getSymbols();
  }

  private Mapdata loadMapdata(File mapdataFile) {
    // mapdata file not available - it is assumed to be empty
    if (mapdataFile == null || !mapdataFile.exists())
      return new Mapdata();

    try {
      JsonObject mapdata = Json.createReader(new InputStreamReader(new FileInputStream(mapdataFile), "utf-8")).readObject();

      List<String> expectedSources = JSONUtils.getStringList(mapdata, SOURCES_PROPERTY);
      List<String> expectedSourcesContent = JSONUtils.getStringList(mapdata, SOURCES_CEONTENT_PROPERTY);
      List<String> expectedSymbols = JSONUtils.getStringList(mapdata, SYMBOLS_PROPERTY);
      List<String> interpolatedSymbols = JSONUtils.getStringList(mapdata, CALCULATED_SYMBOLS_PROPERTY);
      String file = JSONUtils.getString(mapdata, CSS_FILE_PROPERTY);
      String sourceRoot = JSONUtils.getString(mapdata, SOURCE_ROOT_PROPERTY);

      return new Mapdata(expectedSources, expectedSourcesContent, expectedSymbols, interpolatedSymbols, file, sourceRoot);
    } catch (Throwable e) {
      throw new RuntimeException(e);
    }
  }

  private void loadMappedSourceFiles(Collection<String> originalSources, String root, Collection<String> originalSourcesContent) {
    Iterator<String> iterator = originalSourcesContent.iterator();
    for (String name : originalSources)
      try {
        String content = getFileContent(root, name, iterator.next());
        MappedFile mapped = toMappedFile(name, content);
        mappedFiles.put(name, mapped);
      } catch (Throwable th) {
        throw new RuntimeException("Could not read source file " + name, th);
      }
  }

  private void initializeGeneratedCss(CompilationResult compilationResult) {
    cssFile = toMappedFile("", compilationResult.getCss());
  }

  private MappedFile toMappedFile(String name, String content) {
    String[] lines = content.split("\r?\n|\r");
    MappedFile mapped = new MappedFile(lines, name);
    return mapped;
  }

  private String getFileContent(String root, String name, String fallbackContent) throws UnsupportedEncodingException, IOException, FileNotFoundException {
    if (contents.containsKey(name))
      return contents.get(name);

    String filename = URLDecoder.decode(name, "utf-8");
    File sourcefile = toFile(filename);
    
    File file = sourcefile.isAbsolute()? sourcefile : new File(root + sourcefile.getPath());
    if (fallbackContent!=null && !file.exists()) {
      return fallbackContent;
    }
    String content = IOUtils.toString(new InputStreamReader(new FileInputStream(file), "utf-8"));
    return content;
  }

  private File toFile(String filename) {
    //this is not a production quality, but works well enough for tests
    try {
      return new File((new URI(filename)).getPath());
    } catch (URISyntaxException e) {
      return new File(filename);
    }
  }

  private final class SymbolsCollector implements EntryVisitor {

    private final Set<String> symbols = new HashSet<String>();

    private SymbolsCollector() {
    }

    @Override
    public void visit(String sourceName, String sourceContent, String symbolName, FilePosition sourceStartPosition, FilePosition startPosition, FilePosition endPosition) {
      if (symbolName != null)
        symbols.add(symbolName);
    }

    public Set<String> getSymbols() {
      return symbols;
    }

  }

  class MappingEntryValidation implements EntryVisitor {

    private Mapdata mapdata;

    public MappingEntryValidation(Mapdata mapdata) {
      this.mapdata = mapdata;
    }

    @Override
    public void visit(String sourceName, String sourceContent, String symbolName, FilePosition sourceStartPosition, FilePosition startPosition, FilePosition endPosition) {
      MappedFile mappedFile = mappedFiles.get(sourceName);
      if (symbolName != null && !mapdata.isInterpolated(symbolName)) {
        String sourceSnippet = mappedFile.getSnippet(sourceStartPosition, symbolName.length());

        assertNotNull(sourceName + ": css symbol " + symbolName + " " + ts(startPosition) + " is mapped non-existent source position  " + ts(sourceStartPosition), sourceSnippet);
        assertEquals(sourceName + ": css symbol " + symbolName + " " + ts(startPosition) + " is mapped to less " + sourceSnippet + " " + ts(sourceStartPosition), symbolName, sourceSnippet);
      }
      if (symbolName != null && cssFile.isAvailable()) {
        String cssSnippet = cssFile.getSnippet(startPosition, symbolName.length());
        assertEquals(cssFile.getName() + ": position " + ts(startPosition) + " should contain " + symbolName + " it has " + cssSnippet + " instead", symbolName, cssSnippet);
      }
    }

    private String ts(FilePosition p) {
      StringBuilder builder = new StringBuilder();
      builder.append("[").append(p.getLine() + 1).append(":").append(p.getColumn() + 1).append("]");
      return builder.toString();
    }

  }
}

class MappedFile {

  private final String name;
  private final String[] lines;

  public MappedFile() {
    this(new String[0], null);
  }

  public MappedFile(String[] lines, String name) {
    this.lines = lines;
    this.name = name;
  }

  public boolean isAvailable() {
    return name != null;
  }

  public String getSnippet(FilePosition start, int length) {
    if (start.getLine() >= lines.length)
      return null;

    String line = lines[start.getLine()];

    int startColumn = start.getColumn();
    int end = startColumn + length;
    if (end >= line.length())
      end = line.length();

    String substring = line.substring(startColumn, end);
    return substring;
  }

  public String getName() {
    return name;
  }

}

class Mapdata {

  private List<String> sources = null;
  private List<String> sourcesContent = null;
  private List<String> symbols = null;
  private List<String> interpolatedSymbols = null;
  private String file;
  private String sourceRoot;

  public Mapdata(List<String> sources, List<String> sourcesContent, List<String> symbols, List<String> interpolatedSymbols, String file, String sourceRoot) {
    this.sources = sources;
    this.sourcesContent = sourcesContent;
    this.symbols = symbols;
    this.interpolatedSymbols = interpolatedSymbols;
    this.file = file;
    this.sourceRoot = sourceRoot;
  }

  public Mapdata() {
  }

  public boolean hasSources() {
    return sources != null;
  }

  public boolean hasSourcesContent() {
    return sourcesContent != null;
  }

  public List<String> getSources() {
    return sources;
  }

  public Collection<String> getSourcesContent() {
    return sourcesContent;
  }

  public List<String> getSymbols() {
    return symbols;
  }

  public boolean hasSymbols() {
    return symbols != null;
  }

  public List<String> getInterpolatedSymbols() {
    return interpolatedSymbols;
  }

  public boolean hasInterpolatedSymbols() {
    return interpolatedSymbols != null;
  }

  public boolean isInterpolated(String symbolName) {
    if (!hasInterpolatedSymbols())
      return false;

    return interpolatedSymbols.contains(SourceMapValidator.ALL) || interpolatedSymbols.contains(symbolName);
  }

  public String getFile() {
    return file;
  }

  public boolean hasFile() {
    return file != null;
  }

  public String getSourceRoot() {
    return sourceRoot;
  }

  public boolean hasSourceRoot() {
    return sourceRoot != null;
  }

}