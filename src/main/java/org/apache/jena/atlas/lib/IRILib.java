package org.apache.jena.atlas.lib;

import org.apache.jena.atlas.AtlasException;
import org.apache.jena.base.Sys;

import java.io.File;
import java.io.IOError;
import java.nio.file.Path;

/**
 * Override to encode '/' in URI.
 *
 * According to RFC 3986 the encoding is NOT required for '/'.
 * If '/' appears in the query string, it is RFC-compliant to leave it un-encoded.
 * Thus, the original IRILib is working properly BUT there are some public SPARQL endpoints with very strict URI validation.
 *
 * The only override is by adding '/' in charsQueryFrag.
 */
public class IRILib {

    private static char[] uri_reserved = new char[]{'!', '$', '&', '\'', '(', ')', '*', '+', ',', ';', '=', ':', '/', '?', '#', '[', ']', '@'};
    private static char[] uri_non_chars = new char[]{'%', '"', '<', '>', '{', '}', '|', '\\', '`', '^', ' ', '\n', '\r', '\t', '£'};
    private static char[] charsComponent = new char[]{'!', '$', '&', '\'', '(', ')', '*', '+', ',', ';', '=', ':', '/', '?', '#', '[', ']', '@', '%', '"', '<', '>', '{', '}', '|', '\\', '`', '^', ' ', '\n', '\r', '\t', '£'};
    private static char[] charsFilename = new char[]{'!', '$', '&', '\'', '(', ')', '*', '+', ',', ';', '=', '?', '#', '[', ']', '@', '%', '"', '<', '>', '{', '}', '|', '\\', '`', '^', ' ', '\n', '\r', '\t', '£'};
    private static char[] charsPath = new char[]{'!', '$', '&', '\'', '(', ')', '*', '+', ',', ';', '=', '?', '#', '[', ']', '%', '"', '<', '>', '{', '}', '|', '\\', '`', '^', ' ', '\n', '\r', '\t', '£'};
    private static char[] charsQueryFrag = new char[]{'!', '$', '&', '\'', '(', ')', '*', '+', ',', ';', '=', '#', '[', ']', '"', '%', '<', '>', '{', '}', '|', '\\', '`', '^', ' ', '\n', '\r', '\t', '£', '/'};
    static final String cwd;
    static final String cwdURL;

    public IRILib() {
    }

    public static String fileToIRI(File f) {
        return filenameToIRI(f.getAbsolutePath());
    }

    public static String filenameToIRI(String fn) {
        if (fn == null) {
            return cwdURL;
        } else if (fn.length() == 0) {
            return cwdURL;
        } else {
            return fn.startsWith("file:") ? normalizeFilenameURI(fn) : plainFilenameToURL(fn);
        }
    }

    public static String IRIToFilename(String iri) {
        if (!iri.startsWith("file:")) {
            throw new AtlasException("Not a file: URI: " + iri);
        } else {
            String fn;
            if (iri.startsWith("file:///")) {
                fn = iri.substring("file://".length());
            } else {
                fn = iri.substring("file:".length());
            }

            fn = fixupWindows(fn);
            return decodeHex(fn);
        }
    }

    private static String plainFilenameToURL(String fn) {
        boolean trailingSlash = fn.endsWith("/");
        fn = fixupWindows(fn);

        try {
            fn = Path.of(fn).toAbsolutePath().normalize().toString();
        } catch (IOError var3) {
        }

        if (trailingSlash && !fn.endsWith("/")) {
            fn = fn + "/";
        }

        if (Sys.isWindows) {
            if (windowsDrive(fn, 0)) {
                fn = "/" + fn;
            }

            fn = fn.replace('\\', '/');
        }

        fn = encodeFileURL(fn);
        return "file://" + fn;
    }

    private static String fixupWindows(String fn) {
        if (Sys.isWindows && fn.length() >= 3 && fn.charAt(0) == '/' && windowsDrive(fn, 1)) {
            fn = fn.substring(1);
        }

        return fn;
    }

    private static boolean windowsDrive(String fn, int i) {
        return fn.length() >= 2 + i && fn.charAt(1 + i) == ':' && isA2Z(fn.charAt(i));
    }

    private static boolean isA2Z(char ch) {
        return 'a' <= ch && ch <= 'z' || 'A' <= ch && ch <= 'Z';
    }

    private static String normalizeFilenameURI(String fn) {
        String fn2;
        if (!fn.startsWith("file:/")) {
            fn2 = fn.substring("file:".length());
            return plainFilenameToURL(fn2);
        } else if (fn.startsWith("file:///")) {
            return fn;
        } else if (fn.startsWith("file://")) {
            return fn;
        } else {
            fn2 = fn.substring("file:".length());
            return plainFilenameToURL(fn2);
        }
    }

    public static String encodeUriComponent(String string) {
        String encStr = StrUtils.encodeHex(string, '%', charsComponent);
        return encStr;
    }

    public static String encodeUriQueryFrag(String string) {
        String encStr = StrUtils.encodeHex(string, '%', charsQueryFrag);
        return encStr;
    }

    public static String encodeFileURL(String string) {
        String encStr = StrUtils.encodeHex(string, '%', charsFilename);
        return encStr;
    }

    public static String encodeUriPath(String uri) {
        uri = StrUtils.encodeHex(uri, '%', charsPath);
        return uri;
    }

    public static String encodeNonASCII(String string) {
        if (!containsNonASCII(string)) {
            return string;
        } else {
            byte[] bytes = StrUtils.asUTF8bytes(string);
            StringBuilder sw = new StringBuilder();
            byte[] var3 = bytes;
            int var4 = bytes.length;

            for(int var5 = 0; var5 < var4; ++var5) {
                byte b = var3[var5];
                if (b > 0) {
                    sw.append((char)b);
                } else {
                    int hi = (b & 240) >> 4;
                    int lo = b & 15;
                    sw.append('%');
                    sw.append(Chars.hexDigitsUC[hi]);
                    sw.append(Chars.hexDigitsUC[lo]);
                }
            }

            return sw.toString();
        }
    }

    public static boolean containsNonASCII(String string) {
        for(int i = 0; i < string.length(); ++i) {
            char ch = string.charAt(i);
            if (ch >= 127) {
                return true;
            }
        }

        return false;
    }

    public static String decodeHex(String string) {
        return StrUtils.decodeHex(string, '%');
    }

    static {
        String x = (new File(".")).getAbsolutePath();
        x = x.substring(0, x.length() - 1);
        cwd = x;
        cwdURL = plainFilenameToURL(cwd);
    }

}
