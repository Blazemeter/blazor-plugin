package com.blazemeter.jmeter.blazor.recorder;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.apache.jmeter.gui.action.template.TemplateManager;
import org.apache.jmeter.util.JMeterUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Installs the Blazor recorder test plan into JMeter's template list, the same
 * way the Auto Correlation recorder publishes {@code bzm - Auto Correlation Recorder}.
 */
public final class RecorderTemplate {

    static final String NAME = "bzm - Blazor Recorder";

    private static final Logger LOG = LoggerFactory.getLogger(RecorderTemplate.class);
    private static final String JMX = "blazor-recorder.jmx";
    private static final String DESCRIPTION = "blazor-recorder-template-description.xml";

    private RecorderTemplate() {
    }

    /**
     * Copy the plan into {@code bin/templates} and register it. Safe to call
     * when JMeter has no home yet.
     */
    public static void install() {
        String bin = JMeterUtils.getJMeterBinDir();
        if (bin == null || bin.isBlank()) {
            return;
        }
        try {
            if (install(java.nio.file.Path.of(bin, "templates"))) {
                TemplateManager.getInstance().reset();
            }
        } catch (RuntimeException ex) {
            LOG.warn("Could not install the Blazor recorder template", ex);
        }
    }

    /**
     * Writes the template into {@code templatesDir}. Returns whether a file changed.
     */
    static boolean install(java.nio.file.Path templatesDir) {
        String plan = resource("/templates/" + JMX);
        String description = resource("/templates/" + DESCRIPTION);
        if (plan == null || description == null) {
            LOG.warn("Blazor recorder template resources are missing from the jar");
            return false;
        }
        try {
            Files.createDirectories(templatesDir);
            boolean changed = writeIfDifferent(templatesDir.resolve(JMX), plan);
            changed = register(templatesDir.resolve("templates.xml"), description) || changed;
            if (changed) {
                LOG.info("Installed JMeter template {}", NAME);
            }
            return changed;
        } catch (IOException ex) {
            LOG.warn("Could not install the Blazor recorder template in {}", templatesDir, ex);
            return false;
        }
    }

    private static boolean register(java.nio.file.Path index, String description) throws IOException {
        if (!Files.isRegularFile(index)) {
            return false;
        }
        String xml = Files.readString(index, StandardCharsets.UTF_8);
        String marker = "<name>" + NAME + "</name>";
        if (xml.contains(marker)) {
            return false;
        }
        int close = xml.lastIndexOf("</templates>");
        if (close < 0) {
            LOG.warn("templates.xml has no </templates> element, so {} was not registered", NAME);
            return false;
        }
        String updated = xml.substring(0, close) + description.strip() + System.lineSeparator() + xml.substring(close);
        Files.writeString(index, updated, StandardCharsets.UTF_8);
        return true;
    }

    private static boolean writeIfDifferent(java.nio.file.Path file, String contents) throws IOException {
        if (Files.isRegularFile(file) && contents.equals(Files.readString(file, StandardCharsets.UTF_8))) {
            return false;
        }
        Files.writeString(file, contents, StandardCharsets.UTF_8);
        return true;
    }

    private static String resource(String name) {
        try (InputStream in = RecorderTemplate.class.getResourceAsStream(name)) {
            if (in == null) {
                return null;
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            LOG.warn("Could not read {}", name, ex);
            return null;
        }
    }
}
