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
package org.apache.tika.parser.chm;

import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;
import org.apache.tika.exception.TikaException;

import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.Parser;
import org.apache.tika.parser.chm.accessor.ChmDirectoryListingSet;
import org.apache.tika.parser.chm.accessor.DirectoryListingEntry;
import org.apache.tika.parser.chm.core.ChmExtractor;
import org.apache.tika.sax.BodyContentHandler;
import org.ccil.cowan.tagsoup.HTMLSchema;
import org.ccil.cowan.tagsoup.Schema;
import static org.junit.Assert.assertEquals;
import org.junit.Test;
import org.xml.sax.Attributes;
import org.xml.sax.ContentHandler;
import org.xml.sax.Locator;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

public class TestChmExtraction {

    private final Parser parser = new ChmParser();

    private final List<String> files = Arrays.asList(
            "/test-documents/testChm.chm",
            "/test-documents/testChm3.chm");

    @Test
    public void testGetText() throws Exception {
        BodyContentHandler handler = new BodyContentHandler();
        new ChmParser().parse(
                new ByteArrayInputStream(TestParameters.chmData),
                handler, new Metadata(), new ParseContext());
        assertTrue(handler.toString().contains(
                "The TCard method accepts only numeric arguments"));
    }

    @Test
    public void testChmParser() throws Exception{
        for (String fileName : files) {
            InputStream stream =
                    TestChmExtraction.class.getResourceAsStream(fileName);
            try {
                BodyContentHandler handler = new BodyContentHandler(-1);
                parser.parse(stream, handler, new Metadata(), new ParseContext());
                assertTrue(!handler.toString().isEmpty());
            } finally {
                stream.close();
            }
        }
    }

    @Test
    public void testExtractChmEntries() throws TikaException, IOException{
        for (String fileName : files) {
            InputStream stream =
                    TestChmExtraction.class.getResourceAsStream(fileName);
            try {
                testExtractChmEntry(stream);
            } finally {
                stream.close();
            }
        }
    }
    
    public void testExtractChmEntry(InputStream stream) throws TikaException, IOException{
        ChmExtractor chmExtractor = new ChmExtractor(stream);
        ChmDirectoryListingSet entries = chmExtractor.getChmDirList();
        final Pattern htmlPairP = Pattern.compile("\\Q<html\\E.+\\Q</html>\\E"
                , Pattern.CASE_INSENSITIVE | Pattern.MULTILINE | Pattern.DOTALL);
        
        int count = 0;
        for (DirectoryListingEntry directoryListingEntry : entries.getDirectoryListingEntryList()) {
            byte[] data = chmExtractor.extractChmEntry(directoryListingEntry);
            final String lowName = directoryListingEntry.getName().toLowerCase();
            if (lowName.endsWith(".html")
                    || lowName.endsWith(".htm")
                    || lowName.endsWith(".hhk")
                    || lowName.endsWith(".hhc")
                    //|| name.endsWith(".bmp")
                    ) {
                //validate html
                String html = new String(data);
                if (! htmlPairP.matcher(html).find()) {
                    System.err.println(lowName + " is invalid.");
                    System.err.println(html);
                    throw new TikaException("Invalid xhtml file : " + directoryListingEntry.getName());
                }
//                else {
//                    System.err.println(directoryListingEntry.getName() + " is valid.");
//                }
            }
            ++count;
        }
    }
    

    @Test
    public void testMultiThreadedChmExtraction() throws InterruptedException {
        ExecutorService executor = Executors.newFixedThreadPool(TestParameters.NTHREADS);
        for (int i = 0; i < TestParameters.NTHREADS; i++) {
            executor.execute(new Runnable() {
                public void run() {
                    for (String fileName : files) {
                        InputStream stream = null;
                        try {
                            stream = TestChmExtraction.class.getResourceAsStream(fileName);
                            BodyContentHandler handler = new BodyContentHandler(-1);
                            parser.parse(stream, handler, new Metadata(), new ParseContext());
                            assertTrue(!handler.toString().isEmpty());
                        } catch (Exception e) {
                            e.printStackTrace();
                        } finally {
                            try {
                                stream.close();
                            } catch (IOException e) {
                                e.printStackTrace();
                            }
                        }
                    }
                }
            });
        }
        executor.shutdown();
        // Waits until all threads will have finished
        while (!executor.isTerminated()) {
            Thread.sleep(500);
        }
    }
}
