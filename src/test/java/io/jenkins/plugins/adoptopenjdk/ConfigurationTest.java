/*
 * #%L
 * Eclipse Temurin installer Plugin
 * %%
 * Copyright (C) 2026 - 2026 Hannes Wellmann
 * %%
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 * #L%
 */

package io.jenkins.plugins.adoptopenjdk;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.jenkins.plugins.adoptopenjdk.AdoptOpenJDKInstaller.CPU;
import io.jenkins.plugins.adoptopenjdk.AdoptOpenJDKInstaller.Platform;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;
import net.sf.json.JSONArray;
import org.junit.jupiter.api.Test;

public class ConfigurationTest {

    @Test
    void allCPUSupported() throws Exception {
        Collection<String> availableCPUs = fetchList("https://api.adoptium.net/v3/types/architectures");

        Set<String> adoptiumNames =
                Arrays.stream(CPU.values()).map(c -> c.adoptiumName).collect(Collectors.toSet());
        assertEquals(new HashSet<>(availableCPUs), adoptiumNames);
    }

    @Test
    void allOSSupported() throws Exception {
        Collection<String> availableOS = fetchList("https://api.adoptium.net/v3/types/operating_systems");

        Set<String> adoptiumNames =
                Arrays.stream(Platform.values()).map(Platform::getId).collect(Collectors.toSet());
        assertEquals(new HashSet<>(availableOS), adoptiumNames);
    }

    @SuppressWarnings("unchecked")
    private static Collection<String> fetchList(String url) throws IOException, MalformedURLException {
        URI uri = URI.create(url);
        try (InputStream stream = uri.toURL().openStream()) {
            String content = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            JSONArray array = JSONArray.fromObject(content);
            return JSONArray.toCollection(array, String.class);
        }
    }
}
