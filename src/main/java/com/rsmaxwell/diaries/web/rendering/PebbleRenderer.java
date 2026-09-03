package com.rsmaxwell.diaries.web.rendering;

import java.io.IOException;
import java.io.StringWriter;
import java.util.Map;

import io.pebbletemplates.pebble.PebbleEngine;
import io.pebbletemplates.pebble.error.PebbleException;
import io.pebbletemplates.pebble.loader.ClasspathLoader;
import io.pebbletemplates.pebble.template.PebbleTemplate;

public final class PebbleRenderer {
    private final PebbleEngine engine;

    public PebbleRenderer() {
        ClasspathLoader loader = new ClasspathLoader();
        loader.setPrefix("templates");
        loader.setSuffix("");
        this.engine = new PebbleEngine.Builder()
                .loader(loader)
                .autoEscaping(true)
                .build();
    }

    public String render(String templateName, Map<String, Object> model) {
        try {
            PebbleTemplate template = engine.getTemplate(templateName);
            StringWriter writer = new StringWriter();
            template.evaluate(writer, model);
            return writer.toString();
        } catch (PebbleException | IOException exception) {
            throw new IllegalStateException("Unable to render template " + templateName, exception);
        }
    }
}
