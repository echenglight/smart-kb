package com.smartkb.doc;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DocumentParserTest {

    @Test
    void safeFilename_stripsClientPathsAndControlCharacters() {
        assertEquals("report.pdf", DocumentParser.safeFilename("C:\\fakepath\\report.pdf"));
        assertEquals("notes.md", DocumentParser.safeFilename("../../private/notes.md"));
        assertEquals("badname.txt", DocumentParser.safeFilename("bad\u0000name.txt"));
    }

    @Test
    void extOf_isCaseInsensitiveAndUsesFinalSuffix() {
        assertEquals("pdf", DocumentParser.extOf("release.v2.PDF"));
        assertEquals("", DocumentParser.extOf("README"));
    }
}
