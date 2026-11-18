package com.blazemeter.jmeter.blazor.recorder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RecorderTemplateTest {

    @TempDir
    Path templates;

    @Test
    void installsThePlanAndRegistersItOnce() throws Exception {
        Files.writeString(templates.resolve("templates.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <templates>
                </templates>
                """, StandardCharsets.UTF_8);

        assertTrue(RecorderTemplate.install(templates));
        String plan = Files.readString(templates.resolve("blazor-recorder.jmx"));
        assertTrue(plan.contains("testname=\"Blazor Users\""));
        assertTrue(plan.contains("testname=\"View Results Tree\""));
        assertTrue(plan.contains("testclass=\"com.blazemeter.jmeter.blazor.BlazorRecorder\""));
        assertTrue(plan.indexOf("View Results Tree") < plan.indexOf("BlazorRecorder"));

        String index = Files.readString(templates.resolve("templates.xml"));
        assertEquals(1, count(index, "<name>" + RecorderTemplate.NAME + "</name>"));
        assertTrue(index.contains("/bin/templates/blazor-recorder.jmx"));

        assertEquals(false, RecorderTemplate.install(templates));
        assertEquals(1, count(Files.readString(templates.resolve("templates.xml")),
                "<name>" + RecorderTemplate.NAME + "</name>"));
    }

    private static int count(String text, String needle) {
        int found = 0;
        int from = 0;
        while (true) {
            int at = text.indexOf(needle, from);
            if (at < 0) {
                return found;
            }
            found++;
            from = at + needle.length();
        }
    }
}
