package com.lunarphoton.pcauthenticator;

import android.graphics.Color;
import android.graphics.Typeface;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class TerminalColorizer {

    private static final int COLOR_DEFAULT = Color.parseColor("#E2E8F0");      // Crisp off-white
    private static final int COLOR_MUTED = Color.parseColor("#64748B");        // Slate 500
    private static final int COLOR_DIVIDER = Color.parseColor("#334155");      // Slate 700
    private static final int COLOR_CYAN = Color.parseColor("#38BDF8");         // Sky 400
    private static final int COLOR_LIGHT_CYAN = Color.parseColor("#7DD3FC");   // Sky 300
    private static final int COLOR_GREEN = Color.parseColor("#34D399");        // Emerald 400
    private static final int COLOR_BRIGHT_GREEN = Color.parseColor("#10B981"); // Emerald 500
    private static final int COLOR_RED = Color.parseColor("#F87171");          // Red 400
    private static final int COLOR_YELLOW = Color.parseColor("#FBBF24");       // Amber 400
    private static final int COLOR_AMBER = Color.parseColor("#F59E0B");        // Amber 500
    private static final int COLOR_PURPLE = Color.parseColor("#C084FC");       // Purple 400
    private static final int COLOR_VIOLET = Color.parseColor("#A78BFA");       // Violet 400
    private static final int COLOR_WHITE = Color.parseColor("#FFFFFF");        // Pure White

    private static final Pattern ANSI_PATTERN = Pattern.compile("\u001B\\[([0-9;]*)m");
    private static final Pattern ANSI_OTHER_PATTERN = Pattern.compile("\u001B\\[[?0-9;]*[a-zA-Z]");
    private static final Pattern DIVIDER_PATTERN = Pattern.compile("^[─\\-=*_~]{4,}$");
    private static final Pattern TOOL_PATTERN = Pattern.compile("^[●○]\\s+([A-Za-z0-9_]+)(\\(.*?\\))?(.*)$");
    private static final Pattern THOUGHT_PATTERN = Pattern.compile("^[▸]\\s+(Thought for\\s+)(.*?)$");
    private static final Pattern SPINNER_PATTERN = Pattern.compile("^[⣻⡿⠋⠙⠹]\\s+(.*)$");
    private static final Pattern PROMPT_PATTERN = Pattern.compile("^([>$❯➜])\\s*(.*)$");
    private static final Pattern SHELL_PROMPT_PATTERN = Pattern.compile("^([a-zA-Z0-9_.\\-]+@[a-zA-Z0-9_.\\-]+:[^$#]*[$#])\\s*(.*)$");
    private static final Pattern PATH_PATTERN = Pattern.compile("(~?/[a-zA-Z0-9_.\\-/]+|https?://[a-zA-Z0-9_.\\-/]+)");

    /**
     * Pre-formats raw terminal text for clean display on a mobile viewport:
     * - Truncates excessively wide box-drawing divider lines (e.g. 137 dashes) to targetColumns
     * - Collapses huge internal whitespace paddings (used by desktop TUIs to right-align status items)
     * - Strips non-color ANSI control characters
     */
    public static String formatTerminalText(String text, boolean wrapMode, int targetColumns) {
        if (text == null || text.isEmpty()) return "";

        int maxCols = targetColumns > 20 ? targetColumns : 45;
        StringBuilder sb = new StringBuilder();
        String[] lines = text.split("\n", -1);

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];

            if (wrapMode) {
                String trimmed = line.trim();
                // 1. Divider line adaptation: fit line width to avoid 4-line wrapped borders
                if (DIVIDER_PATTERN.matcher(trimmed).matches()) {
                    char ch = trimmed.charAt(0);
                    StringBuilder div = new StringBuilder();
                    for (int c = 0; c < maxCols; c++) {
                        div.append(ch);
                    }
                    line = div.toString();
                } else if (line.length() > 20) {
                    // 2. Collapse internal runs of 6+ spaces in the middle of a line (e.g. TUI status footers)
                    // preserve leading spaces (code indent)
                    int firstNonSpace = 0;
                    while (firstNonSpace < line.length() && line.charAt(firstNonSpace) == ' ') {
                        firstNonSpace++;
                    }
                    if (firstNonSpace < line.length()) {
                        String indent = line.substring(0, firstNonSpace);
                        String body = line.substring(firstNonSpace);
                        body = body.replaceAll(" {6,}", "   ");
                        line = indent + body;
                    }
                }
            }

            sb.append(line);
            if (i < lines.length - 1) {
                sb.append("\n");
            }
        }
        return sb.toString();
    }

    /**
     * Colorizes terminal text into a rich Spannable:
     * - Parses ANSI escape codes if present
     * - Falls back to semantic terminal highlighting for plain-text streams (Konsole getAllDisplayedText)
     */
    public static CharSequence colorize(String text, boolean wrapMode, int targetColumns) {
        if (text == null || text.isEmpty()) return "";

        String formatted = formatTerminalText(text, wrapMode, targetColumns);

        // Check if string contains ANSI color codes
        if (formatted.contains("\u001B[")) {
            return parseAnsiColors(formatted);
        }

        return parseSemanticColors(formatted);
    }

    private static CharSequence parseAnsiColors(String text) {
        // Strip non-color ANSI control codes
        String clean = ANSI_OTHER_PATTERN.matcher(text).replaceAll("");

        SpannableStringBuilder ssb = new SpannableStringBuilder();
        Matcher matcher = ANSI_PATTERN.matcher(clean);

        int lastEnd = 0;
        int currentFg = COLOR_DEFAULT;
        boolean isBold = false;
        boolean isUnderline = false;

        while (matcher.find()) {
            int start = matcher.start();
            if (start > lastEnd) {
                String chunk = clean.substring(lastEnd, start);
                appendStyled(ssb, chunk, currentFg, isBold, isUnderline);
            }

            String params = matcher.group(1);
            if (params == null || params.isEmpty() || "0".equals(params)) {
                currentFg = COLOR_DEFAULT;
                isBold = false;
                isUnderline = false;
            } else {
                String[] codes = params.split(";");
                for (int i = 0; i < codes.length; i++) {
                    int code = 0;
                    try {
                        code = Integer.parseInt(codes[i]);
                    } catch (NumberFormatException ignored) {}

                    if (code == 0) {
                        currentFg = COLOR_DEFAULT;
                        isBold = false;
                        isUnderline = false;
                    } else if (code == 1) {
                        isBold = true;
                    } else if (code == 4) {
                        isUnderline = true;
                    } else if (code >= 30 && code <= 37) {
                        currentFg = getAnsiStandardColor(code - 30, false);
                    } else if (code == 39) {
                        currentFg = COLOR_DEFAULT;
                    } else if (code >= 90 && code <= 97) {
                        currentFg = getAnsiStandardColor(code - 90, true);
                    } else if (code == 38 && i + 2 < codes.length && "5".equals(codes[i + 1])) {
                        // 256 color
                        try {
                            int c256 = Integer.parseInt(codes[i + 2]);
                            currentFg = get256Color(c256);
                            i += 2;
                        } catch (Exception ignored) {}
                    } else if (code == 38 && i + 4 < codes.length && "2".equals(codes[i + 1])) {
                        // Truecolor rgb
                        try {
                            int r = Integer.parseInt(codes[i + 2]);
                            int g = Integer.parseInt(codes[i + 3]);
                            int b = Integer.parseInt(codes[i + 4]);
                            currentFg = Color.rgb(r, g, b);
                            i += 4;
                        } catch (Exception ignored) {}
                    }
                }
            }
            lastEnd = matcher.end();
        }

        if (lastEnd < clean.length()) {
            appendStyled(ssb, clean.substring(lastEnd), currentFg, isBold, isUnderline);
        }

        return ssb;
    }

    private static void appendStyled(SpannableStringBuilder ssb, String chunk, int fg, boolean bold, boolean underline) {
        if (chunk.isEmpty()) return;
        int s = ssb.length();
        ssb.append(chunk);
        int e = ssb.length();
        ssb.setSpan(new ForegroundColorSpan(fg), s, e, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        if (bold) {
            ssb.setSpan(new StyleSpan(Typeface.BOLD), s, e, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
    }

    private static int getAnsiStandardColor(int idx, boolean bright) {
        switch (idx) {
            case 0: return bright ? COLOR_MUTED : Color.parseColor("#475569");
            case 1: return bright ? COLOR_RED : Color.parseColor("#DC2626");
            case 2: return bright ? COLOR_GREEN : Color.parseColor("#16A34A");
            case 3: return bright ? COLOR_YELLOW : Color.parseColor("#D97706");
            case 4: return bright ? Color.parseColor("#60A5FA") : Color.parseColor("#2563EB");
            case 5: return bright ? COLOR_PURPLE : Color.parseColor("#9333EA");
            case 6: return bright ? COLOR_CYAN : Color.parseColor("#0891B2");
            case 7: default: return bright ? COLOR_WHITE : COLOR_DEFAULT;
        }
    }

    private static int get256Color(int idx) {
        if (idx < 16) {
            return getAnsiStandardColor(idx % 8, idx >= 8);
        } else if (idx >= 232) {
            int gray = 8 + (idx - 232) * 10;
            return Color.rgb(gray, gray, gray);
        } else {
            idx -= 16;
            int b = (idx % 6) * 51;
            int g = ((idx / 6) % 6) * 51;
            int r = (idx / 36) * 51;
            return Color.rgb(r, g, b);
        }
    }

    /**
     * Semantic syntax highlighting for plain-text terminal output (Konsole Session.getAllDisplayedText).
     */
    private static CharSequence parseSemanticColors(String text) {
        SpannableStringBuilder ssb = new SpannableStringBuilder();
        String[] lines = text.split("\n", -1);

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            int lineStart = ssb.length();
            ssb.append(line);
            int lineEnd = ssb.length();

            colorSingleLine(ssb, line, lineStart, lineEnd);

            if (i < lines.length - 1) {
                ssb.append("\n");
            }
        }
        return ssb;
    }

    private static void colorSingleLine(SpannableStringBuilder ssb, String line, int s, int e) {
        String trimmed = line.trim();
        if (trimmed.isEmpty()) return;

        // 1. Box-drawing divider lines
        if (DIVIDER_PATTERN.matcher(trimmed).matches()) {
            ssb.setSpan(new ForegroundColorSpan(COLOR_DIVIDER), s, e, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            return;
        }

        // 2. Tool / Agent calls: ● Bash(...), ● Read(...), etc.
        Matcher toolM = TOOL_PATTERN.matcher(trimmed);
        if (toolM.matches()) {
            int relCircle = line.indexOf(trimmed.charAt(0));
            if (relCircle >= 0) {
                // Badge circle: Cyan
                ssb.setSpan(new ForegroundColorSpan(COLOR_CYAN), s + relCircle, s + relCircle + 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                ssb.setSpan(new StyleSpan(Typeface.BOLD), s + relCircle, s + relCircle + 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);

                String toolName = toolM.group(1);
                int relTool = line.indexOf(toolName, relCircle + 1);
                if (relTool >= 0) {
                    // Tool Name: Bright White Bold
                    ssb.setSpan(new ForegroundColorSpan(COLOR_WHITE), s + relTool, s + relTool + toolName.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                    ssb.setSpan(new StyleSpan(Typeface.BOLD), s + relTool, s + relTool + toolName.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);

                    int relArgs = relTool + toolName.length();
                    // Args / Target: Light Cyan
                    ssb.setSpan(new ForegroundColorSpan(COLOR_LIGHT_CYAN), s + relArgs, e, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);

                    // Shortcut / hint: e.g. (ctrl+o to expand)
                    int relHint = line.indexOf("(ctrl+o", relArgs);
                    if (relHint >= 0) {
                        ssb.setSpan(new ForegroundColorSpan(COLOR_MUTED), s + relHint, e, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                    }
                    return;
                }
            }
        }

        // 3. Completed / success badge
        if (trimmed.startsWith("✔") || trimmed.startsWith("✓")) {
            ssb.setSpan(new ForegroundColorSpan(COLOR_BRIGHT_GREEN), s, e, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            return;
        }

        // 4. Failed / error badge
        if (trimmed.startsWith("✖") || trimmed.startsWith("✗")) {
            ssb.setSpan(new ForegroundColorSpan(COLOR_RED), s, e, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            return;
        }

        // 5. Thinking block: ▸ Thought for ...
        Matcher thoughtM = THOUGHT_PATTERN.matcher(trimmed);
        if (thoughtM.matches()) {
            int relIcon = line.indexOf('▸');
            if (relIcon >= 0) {
                ssb.setSpan(new ForegroundColorSpan(COLOR_PURPLE), s + relIcon, s + relIcon + 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                ssb.setSpan(new StyleSpan(Typeface.BOLD), s + relIcon, s + relIcon + 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            int relLabel = line.indexOf("Thought for");
            if (relLabel >= 0) {
                ssb.setSpan(new ForegroundColorSpan(COLOR_VIOLET), s + relLabel, s + relLabel + 11, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                // Time & tokens in Amber
                ssb.setSpan(new ForegroundColorSpan(COLOR_YELLOW), s + relLabel + 11, e, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            return;
        }

        // 6. Spinner / In-progress: ⣻ Running command...
        Matcher spinnerM = SPINNER_PATTERN.matcher(trimmed);
        if (spinnerM.matches()) {
            int relSpin = line.indexOf(trimmed.charAt(0));
            if (relSpin >= 0) {
                ssb.setSpan(new ForegroundColorSpan(COLOR_AMBER), s + relSpin, s + relSpin + 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                ssb.setSpan(new ForegroundColorSpan(COLOR_YELLOW), s + relSpin + 1, e, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            return;
        }

        // 7. Footer status bar: esc to cancel ... Gemini 3.8 Flash · high
        if (trimmed.contains("esc to cancel")) {
            int relEsc = line.indexOf("esc to cancel");
            if (relEsc >= 0) {
                ssb.setSpan(new ForegroundColorSpan(COLOR_MUTED), s, s + relEsc + 13, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            // Model tag
            int relModel = Math.max(line.indexOf("Gemini"), Math.max(line.indexOf("claude"), line.indexOf("gpt")));
            if (relModel >= 0) {
                ssb.setSpan(new ForegroundColorSpan(COLOR_GREEN), s + relModel, e, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                ssb.setSpan(new StyleSpan(Typeface.BOLD), s + relModel, e, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            return;
        }

        // 8. Shell prompt: user@host:~$ cmd
        Matcher shellM = SHELL_PROMPT_PATTERN.matcher(trimmed);
        if (shellM.matches()) {
            String promptPart = shellM.group(1);
            int relPrompt = line.indexOf(promptPart);
            if (relPrompt >= 0) {
                ssb.setSpan(new ForegroundColorSpan(COLOR_GREEN), s + relPrompt, s + relPrompt + promptPart.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                ssb.setSpan(new StyleSpan(Typeface.BOLD), s + relPrompt, s + relPrompt + promptPart.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                // Command typed in White
                ssb.setSpan(new ForegroundColorSpan(COLOR_WHITE), s + relPrompt + promptPart.length(), e, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                return;
            }
        }

        // 9. Prompt symbol: > cmd or $ cmd
        Matcher promptM = PROMPT_PATTERN.matcher(trimmed);
        if (promptM.matches()) {
            int relSym = line.indexOf(trimmed.charAt(0));
            if (relSym >= 0) {
                ssb.setSpan(new ForegroundColorSpan(COLOR_CYAN), s + relSym, s + relSym + 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                ssb.setSpan(new StyleSpan(Typeface.BOLD), s + relSym, s + relSym + 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                ssb.setSpan(new ForegroundColorSpan(COLOR_WHITE), s + relSym + 1, e, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                return;
            }
        }

        // 10. Errors & Exceptions
        String lower = trimmed.toLowerCase();
        if (lower.startsWith("error:") || lower.startsWith("fatal:") || lower.contains("exception") || lower.contains("traceback (most recent") || lower.startsWith("failed")) {
            ssb.setSpan(new ForegroundColorSpan(COLOR_RED), s, e, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            return;
        }

        // 11. Warnings
        if (lower.startsWith("warning:") || lower.startsWith("warn:")) {
            ssb.setSpan(new ForegroundColorSpan(COLOR_YELLOW), s, e, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            return;
        }

        // 12. Success
        if (lower.startsWith("success") || lower.contains("successfully") || lower.equals("ok") || lower.startsWith("passed")) {
            ssb.setSpan(new ForegroundColorSpan(COLOR_GREEN), s, e, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            return;
        }

        // 13. Default line text: Crisp off-white
        ssb.setSpan(new ForegroundColorSpan(COLOR_DEFAULT), s, e, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);

        // Highlight any file paths or URLs inside the line
        Matcher pathM = PATH_PATTERN.matcher(line);
        while (pathM.find()) {
            int pStart = s + pathM.start();
            int pEnd = s + pathM.end();
            ssb.setSpan(new ForegroundColorSpan(COLOR_CYAN), pStart, pEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
    }
}
