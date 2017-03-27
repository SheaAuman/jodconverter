/*
 * Copyright 2004 - 2012 Mirko Nasato and contributors
 *           2016 - 2017 Simon Braconnier and contributors
 *
 * This file is part of JODConverter - Java OpenDocument Converter.
 *
 * JODConverter is an Open Source software: you can redistribute it and/or
 * modify it under the terms of either (at your option) of the following
 * licenses:
 *
 * 1. The GNU Lesser General Public License v3 (or later)
 *    http://www.gnu.org/licenses/lgpl-3.0.txt
 * 2. The Apache License, Version 2.0
 *    http://www.apache.org/licenses/LICENSE-2.0.txt
 */

package org.artofsolving.jodconverter;

import java.io.File;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.annotations.Test;

import org.artofsolving.jodconverter.document.DocumentFormat;
import org.artofsolving.jodconverter.document.DocumentFormatRegistry;
import org.artofsolving.jodconverter.office.DefaultOfficeManagerBuilder;
import org.artofsolving.jodconverter.office.OfficeManager;

@Test(groups = "functional")
public class StressTest {

  private static final Logger logger = LoggerFactory.getLogger(StressTest.class);

  private static final int MAX_CONVERSIONS = 1024;
  private static final int MAX_RUNNING_THREADS = 128;
  private static final int MAX_TASKS_PER_PROCESS = 10;

  private static final String INPUT_EXTENSION = "rtf";
  private static final String OUTPUT_EXTENSION = "pdf";

  /**
   * This test will run multiple parallel conversions, using 8 office processes. Just change the
   * MAX_* constants to control the numbers of conversion, threads and maximum conversion per office
   * process allowed.
   *
   * @throws Exception if an error occurs.
   */
  public void runParallelConversions() throws Exception {

    // Configure the office manager in a way that maximizes possible race conditions.
    final DefaultOfficeManagerBuilder configuration = new DefaultOfficeManagerBuilder();
    configuration.setPortNumbers(new int[] {2002, 2003, 2004, 2005, 2006, 2007, 2008, 2009});
    configuration.setMaxTasksPerProcess(MAX_TASKS_PER_PROCESS);

    final OfficeManager officeManager = configuration.build();
    final OfficeDocumentConverter converter = new OfficeDocumentConverter(officeManager);
    final DocumentFormatRegistry formatRegistry = converter.getFormatRegistry();

    officeManager.start();
    try {
      final File inputFile = new File("src/test/resources/documents/test." + INPUT_EXTENSION);

      final Thread[] threads = new Thread[MAX_RUNNING_THREADS];

      boolean first = true;
      int t = 0;

      final DocumentFormat inputFormat = formatRegistry.getFormatByExtension(INPUT_EXTENSION);
      final DocumentFormat outputFormat = formatRegistry.getFormatByExtension(OUTPUT_EXTENSION);
      for (int i = 0; i < MAX_CONVERSIONS; i++) {
        final File outputFile = File.createTempFile("test", "." + outputFormat.getExtension());
        outputFile.deleteOnExit();

        // Converts the first document without threads to ensure everything is OK.
        if (first) {
          converter.convert(inputFile, outputFile, outputFormat);
          first = false;
        }

        logger.info("Creating thread {}...", t);
        final Runner r = new Runner(inputFile, outputFile, inputFormat, outputFormat, converter);
        threads[t] = new Thread(r);
        threads[t++].start();

        if (t == MAX_RUNNING_THREADS) {
          for (int j = 0; j < t; j++) {
            threads[j].join();
          }
          t = 0;
        }
      }

      // Wait for remaining threads.
      for (int j = 0; j < t; j++) {
        threads[j].join();
      }

    } finally {
      officeManager.stop();
    }
  }

  private class Runner implements Runnable {

    public Runner(
        final File inputFile,
        final File outputFile,
        final DocumentFormat inputFormat,
        final DocumentFormat outputFormat,
        final OfficeDocumentConverter converter) {
      super();

      this.inputFile = inputFile;
      this.outputFile = outputFile;
      this.inputFormat = inputFormat;
      this.outputFormat = outputFormat;
      this.converter = converter;
    }

    private final File inputFile;
    private final File outputFile;
    private final DocumentFormat inputFormat;
    private final DocumentFormat outputFormat;
    private final OfficeDocumentConverter converter;

    @Override
    public void run() {
      try {
        logger.info(
            "-- converting %s to %s... ", inputFormat.getExtension(), outputFormat.getExtension());
        converter.convert(inputFile, outputFile, outputFormat);
        logger.info("done.\n");
      } catch (Exception ex) {
        logger.error(ex.getMessage(), ex);
      }
    }
  }
}
