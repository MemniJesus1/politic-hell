package com.politichell.hud;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Правка YAML «по строкам»: меняет ТОЛЬКО значение одного ключа и не трогает комментарии (Bukkit при сохранении теряет часть комментариев).
 * Понимает обычные «ключ: значение» (bool, числа, строки), разделы и списки (списки — только чтение). Нужен панели /phh.
 */
public final class YamlPatch {
    private YamlPatch() {}

    public enum Kind { SECTION, BOOL, INT, DOUBLE, STRING, LIST }

    /** Один ключ файла. comments — комментарий над ключом и в конце строки; items — элементы списка. */
    public static final class Node {
        public String path, key, raw;
        public int indent, line;
        public Kind kind;
        public final List<String> comments = new ArrayList<>();
        public final List<String> items = new ArrayList<>();
        public String parent() { int i = path.lastIndexOf('.'); return i < 0 ? "" : path.substring(0, i); }
    }

    private static final Pattern KEY = Pattern.compile("^(\\s*)([^\\s#\\-][^:#]*?|\"[^\"]*\"|'[^']*')\\s*:(?:\\s+(.*))?$");

    public static List<String> read(File f) throws IOException { return Files.readAllLines(f.toPath(), StandardCharsets.UTF_8); }

    /** Значение без хвостового комментария «  # …» (если # не внутри кавычек). Второй элемент — сам комментарий или "". */
    static String[] splitInline(String v) {
        boolean q1 = false, q2 = false;
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            if (c == '\'' && !q2) q1 = !q1;
            else if (c == '"' && !q1 && (i == 0 || v.charAt(i - 1) != '\\')) q2 = !q2;
            else if (c == '#' && !q1 && !q2 && (i == 0 || Character.isWhitespace(v.charAt(i - 1)))) return new String[]{v.substring(0, i).trim(), v.substring(i + 1).trim()};
        }
        return new String[]{v.trim(), ""};
    }

    public static List<Node> parse(List<String> lines) {
        List<Node> out = new ArrayList<>();
        List<int[]> stack = new ArrayList<>();           // [indent, index in out]
        List<String> pending = new ArrayList<>();
        Node lastList = null;
        for (int ln = 0; ln < lines.size(); ln++) {
            String raw = lines.get(ln);
            String t = raw.trim();
            if (t.isEmpty()) { pending.clear(); continue; }
            if (t.startsWith("#")) {
                String c = t.replaceFirst("^#+\\s?", "").trim();
                if (!c.isEmpty() && !c.matches("^[═─\\-=_*#\\s]+$")) pending.add(c);
                else if (c.isEmpty() || c.matches("^[═─\\-=_*#\\s]+$")) { /* разделитель — пропускаем */ }
                continue;
            }
            if (t.startsWith("- ") || t.equals("-")) {
                if (lastList != null) lastList.items.add(splitInline(t.substring(1).trim())[0]);
                pending.clear();
                continue;
            }
            Matcher m = KEY.matcher(raw);
            if (!m.matches()) { pending.clear(); continue; }
            int indent = m.group(1).length();
            String key = m.group(2).trim();
            if ((key.startsWith("\"") && key.endsWith("\"")) || (key.startsWith("'") && key.endsWith("'"))) key = key.substring(1, key.length() - 1);
            while (!stack.isEmpty() && stack.get(stack.size() - 1)[0] >= indent) stack.remove(stack.size() - 1);
            String parent = stack.isEmpty() ? "" : out.get(stack.get(stack.size() - 1)[1]).path;
            Node n = new Node();
            n.key = key; n.indent = indent; n.line = ln;
            n.path = parent.isEmpty() ? key : parent + "." + key;
            n.comments.addAll(pending); pending.clear();
            String val = m.group(3) == null ? "" : m.group(3);
            String[] sp = splitInline(val);
            n.raw = sp[0];
            if (!sp[1].isEmpty()) n.comments.add(sp[1]);
            if (n.raw.isEmpty()) {
                boolean list = false;
                for (int k = ln + 1; k < lines.size(); k++) {
                    String nt = lines.get(k).trim();
                    if (nt.isEmpty() || nt.startsWith("#")) continue;
                    list = nt.startsWith("- ") || nt.equals("-");
                    break;
                }
                n.kind = list ? Kind.LIST : Kind.SECTION;
            } else if (n.raw.equals("true") || n.raw.equals("false")) n.kind = Kind.BOOL;
            else if (n.raw.matches("-?\\d+")) n.kind = Kind.INT;
            else if (n.raw.matches("-?\\d+\\.\\d+")) n.kind = Kind.DOUBLE;
            else if (n.raw.startsWith("[") || n.raw.startsWith("{")) { n.kind = Kind.LIST; n.items.add(n.raw); }
            else n.kind = Kind.STRING;
            out.add(n);
            lastList = n.kind == Kind.LIST ? n : null;
            if (n.kind == Kind.SECTION) stack.add(new int[]{indent, out.size() - 1});
        }
        return out;
    }

    public static String unquote(String raw) {
        String s = raw.trim();
        if (s.length() >= 2 && ((s.startsWith("\"") && s.endsWith("\"")) || (s.startsWith("'") && s.endsWith("'")))) {
            s = s.substring(1, s.length() - 1);
            if (raw.trim().startsWith("\"")) s = s.replace("\\\"", "\"").replace("\\\\", "\\");
        }
        return s;
    }

    /** Меняет значение ключа path в файле. Рядом остаётся копия «имя.bak». */
    public static void set(File f, String path, String newValue, boolean quote) throws IOException {
        List<String> lines = read(f);
        for (Node n : parse(lines)) {
            if (!n.path.equals(path) || n.kind == Kind.SECTION || n.kind == Kind.LIST) continue;
            String orig = lines.get(n.line);
            int colon = orig.indexOf(':', n.indent);
            String head = orig.substring(0, colon + 1);
            String tail = orig.substring(colon + 1);
            String inline = "";
            int idx = commentStart(tail);
            if (idx >= 0) inline = "  " + tail.substring(idx).trim().replaceFirst("^", "");
            String v = quote ? "\"" + newValue.replace("\\", "\\\\").replace("\"", "\\\"") + "\"" : newValue;
            lines.set(n.line, head + " " + v + inline);
            Files.copy(f.toPath(), new File(f.getParentFile(), f.getName() + ".bak").toPath(), StandardCopyOption.REPLACE_EXISTING);
            Files.write(f.toPath(), lines, StandardCharsets.UTF_8);
            return;
        }
        throw new IOException("ключ не найден: " + path);
    }

    private static int commentStart(String s) {
        boolean q1 = false, q2 = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\'' && !q2) q1 = !q1;
            else if (c == '"' && !q1 && (i == 0 || s.charAt(i - 1) != '\\')) q2 = !q2;
            else if (c == '#' && !q1 && !q2 && i > 0 && Character.isWhitespace(s.charAt(i - 1))) return i;
        }
        return -1;
    }
}
