/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.tika.parser.ocr;

import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import javax.imageio.ImageIO;

import org.apache.tika.exception.TikaException;
import org.apache.tika.io.IOUtils;
import org.apache.tika.io.TemporaryResources;
import org.apache.tika.io.TikaInputStream;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.mime.MediaType;
import org.apache.tika.parser.AbstractParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.Parser;
import org.apache.tika.parser.external.ExternalParser;
import org.apache.tika.parser.image.ImageParser;
import org.apache.tika.parser.image.PSDParser;
import org.apache.tika.parser.image.TiffParser;
import org.apache.tika.parser.jpeg.JpegParser;
import org.apache.tika.sax.XHTMLContentHandler;
import org.xml.sax.ContentHandler;
import org.xml.sax.SAXException;

/**
 * TesseractOCRParser powered by tesseract-ocr engine. To enable this parser,
 * create a {@link TesseractOCRConfig} object and pass it through a
 * ParseContext. Tesseract-ocr must be installed and on system path or the path
 * to its root folder must be provided:
 * <p>
 * TesseractOCRConfig config = new TesseractOCRConfig();<br>
 * //Needed if tesseract is not on system path<br>
 * config.setTesseractPath(tesseractFolder);<br>
 * parseContext.set(TesseractOCRConfig.class, config);<br>
 * </p>
 * 
 * 
 */
public class TesseractOCRParser extends AbstractParser {

  private static final long serialVersionUID = 1L;

  private static final Set<MediaType> SUPPORTED_TYPES = getTypes();

  private static Set<MediaType> getTypes() {
    HashSet<MediaType> supportedTypes = new HashSet<MediaType>();

    supportedTypes.add(MediaType.image("png"));
    supportedTypes.add(MediaType.image("jpeg"));
    supportedTypes.add(MediaType.image("tiff"));
    supportedTypes.add(MediaType.image("x-ms-bmp"));
    supportedTypes.add(MediaType.image("gif"));

    return supportedTypes;
  }

  @Override
  public Set<MediaType> getSupportedTypes(ParseContext arg0) {
    return SUPPORTED_TYPES;
  }

  private void setEnv(TesseractOCRConfig config, ProcessBuilder pb) {
    if (!config.getTesseractPath().isEmpty()) {
      Map<String, String> env = pb.environment();
      env.put("TESSDATA_PREFIX", config.getTesseractPath());
    }
  }

  public void parse(Image image, ContentHandler handler, Metadata metadata, ParseContext context) throws IOException,
      SAXException, TikaException {

    TemporaryResources tmp = new TemporaryResources();
    FileOutputStream fos = null;
    TikaInputStream tis = null;
    try {
      int w = image.getWidth(null);
      int h = image.getHeight(null);
      BufferedImage bImage = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
      Graphics2D g2 = bImage.createGraphics();
      g2.drawImage(image, 0, 0, null);
      g2.dispose();
      File file = tmp.createTemporaryFile();
      fos = new FileOutputStream(file);
      ImageIO.write(bImage, "png", fos);
      bImage = null;
      tis = TikaInputStream.get(file);
      parse(tis, handler, metadata, context);

    } finally {
      tmp.dispose();
      if (tis != null)
        tis.close();
      if (fos != null)
        fos.close();
    }

  }

  @Override
  public void parse(InputStream stream, ContentHandler handler, Metadata metadata, ParseContext context)
      throws IOException, SAXException, TikaException {

    TesseractOCRConfig config = context.get(TesseractOCRConfig.class);
    if (config == null)
      config = new TesseractOCRConfig();

    String[] checkCmd = { config.getTesseractPath() + "tesseract" };
    // If Tesseract is not on the path, do not try to run OCR.
    if (!ExternalParser.check(checkCmd))
      return;

    XHTMLContentHandler xhtml = new XHTMLContentHandler(handler, metadata);

    TemporaryResources tmp = new TemporaryResources();
    File output = null;
    try {
      TikaInputStream tikaStream = TikaInputStream.get(stream, tmp);
      File input = tikaStream.getFile();
      long size = tikaStream.getLength();

      if (size >= config.getMinFileSizeToOcr() && size <= config.getMaxFileSizeToOcr()) {

        output = tmp.createTemporaryFile();
        doOCR(input, output, config);

        // Tesseract appends .txt to output file name
        output = new File(output.getAbsolutePath() + ".txt");

        if (output.exists())
          extractOutput(new FileInputStream(output), xhtml);

      }

    } finally {
      tmp.dispose();
      if (output != null)
        output.delete();

    }
  }

  /**
   * Run external tesseract-ocr process.
   * 
   * @param input
   *          File to be ocred
   * @param output
   *          File to collect ocr result
   * @param config
   *          Configuration of tesseract-ocr engine
   * @throws TikaException
   *           if the extraction timed out
   * @throws IOException
   *           if an input error occurred
   */
  private void doOCR(File input, File output, TesseractOCRConfig config) throws IOException, TikaException {
    String[] cmd = { config.getTesseractPath() + "tesseract", input.getPath(), output.getPath(), "-l",
        config.getLanguage(), "-psm", config.getPageSegMode() };

    ProcessBuilder pb = new ProcessBuilder(cmd);
    setEnv(config, pb);
    final Process process = pb.start();

    process.getOutputStream().close();
    InputStream out = process.getInputStream();
    InputStream err = process.getErrorStream();

    logStream("OCR MSG", out, input);
    logStream("OCR ERROR", err, input);

    FutureTask<Integer> waitTask = new FutureTask<Integer>(new Callable<Integer>() {
      public Integer call() throws Exception {
        return process.waitFor();
      }
    });

    Thread waitThread = new Thread(waitTask);
    waitThread.start();

    try {
      waitTask.get(config.getTimeout(), TimeUnit.SECONDS);

    } catch (InterruptedException e) {
      waitThread.interrupt();
      process.destroy();
      Thread.currentThread().interrupt();
      throw new TikaException("TesseractOCRParser interrupted", e);

    } catch (ExecutionException e) {
      // should not be thrown

    } catch (TimeoutException e) {
      waitThread.interrupt();
      process.destroy();
      throw new TikaException("TesseractOCRParser timeout", e);
    }

  }

  /**
   * Reads the contents of the given stream and write it to the given XHTML
   * content handler. The stream is closed once fully processed.
   * 
   * @param stream
   *          Stream where is the result of ocr
   * @param xhtml
   *          XHTML content handler
   * @throws SAXException
   *           if the XHTML SAX events could not be handled
   * @throws IOException
   *           if an input error occurred
   */
  private void extractOutput(InputStream stream, XHTMLContentHandler xhtml) throws SAXException, IOException {

    Reader reader = new InputStreamReader(stream, "UTF-8");
    xhtml.startDocument();
    xhtml.startElement("div");
    try {
      char[] buffer = new char[1024];
      for (int n = reader.read(buffer); n != -1; n = reader.read(buffer)) {
        if (n > 0)
          xhtml.characters(buffer, 0, n);
      }
    } finally {
      reader.close();
    }
    xhtml.endElement("div");
    xhtml.endDocument();
  }

  /**
   * Starts a thread that reads the contents of the standard output or error
   * stream of the given process to not block the process. The stream is closed
   * once fully processed.
   */
  private void logStream(final String logType, final InputStream stream, final File file) {
    new Thread() {
      public void run() {
        Reader reader = new InputStreamReader(stream);
        StringBuilder out = new StringBuilder();
        char[] buffer = new char[1024];
        try {
          for (int n = reader.read(buffer); n != -1; n = reader.read(buffer))
            out.append(buffer, 0, n);
        } catch (IOException e) {

        } finally {
          IOUtils.closeQuietly(stream);
        }

        String msg = out.toString();
        // log or discard message?

      }
    }.start();
  }

  private List<Parser> getImageParsers() {
    List<Parser> parsers = new ArrayList<Parser>();
    parsers.add(new ImageParser());
    parsers.add(new PSDParser());
    parsers.add(new TiffParser());
    parsers.add(new JpegParser());
    return parsers;
  }

}
