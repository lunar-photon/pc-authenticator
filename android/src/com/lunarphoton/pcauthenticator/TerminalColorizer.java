package com.lunarphoton.pcauthenticator;

import android.graphics.Color;
import android.graphics.Typeface;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class TerminalColorizer {

    // --- Vibrant High-Contrast Electric Palette (beloved by user from commit 6fa0ad6) ---
    private static final int COLOR_DEFAULT = Color.parseColor("#E2E8F0");      // Crisp off-white / light slate
    private static final int COLOR_MUTED = Color.parseColor("#64748B");        // Slate 500
    private static final int COLOR_DIVIDER = Color.parseColor("#334155");      // Slate 700
    private static final int COLOR_CYAN = Color.parseColor("#38BDF8");         // Sky 400 (Electric Cyan)
    private static final int COLOR_LIGHT_CYAN = Color.parseColor("#7DD3FC");   // Sky 300
    private static final int COLOR_GREEN = Color.parseColor("#34D399");        // Emerald 400
    private static final int COLOR_BRIGHT_GREEN = Color.parseColor("#10B981"); // Emerald 500
    private static final int COLOR_RED = Color.parseColor("#F87171");          // Red 400 (Electric Coral Red)
    private static final int COLOR_YELLOW = Color.parseColor("#FBBF24");       // Amber 400 (Vibrant Gold)
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
    private static final Pattern LIST_ITEM_PATTERN = Pattern.compile("^(\\s*[-•*]\\s+)([^:]+:)(.*)$");
    private static final Pattern NUMBERED_LIST_PATTERN = Pattern.compile("^(\\s*\\d+[.)]\\s+)([^:]+:)(.*)$");
    private static final Pattern MD_HEADING_PATTERN = Pattern.compile("^\\s*#{1,6}\\s+.*$");
    private static final Pattern LABEL_HEADING_PATTERN = Pattern.compile("^\\s*([A-Z][A-Za-z0-9_\\s&\\-/]+:)\\s*$");
    private static final Pattern PATH_OR_CODE_PATTERN = Pattern.compile("(`[^`]+`|~?/[a-zA-Z0-9_.\\-/]+|[a-zA-Z0-9_.\\-]+/[a-zA-Z0-9_.\\-/]+|[a-zA-Z0-9_.\\-]+\\.(?:py|md|java|json|xml|sh|txt|apk|cpp|h|rs|go|js|ts|kt|gradle)|https?://[a-zA-Z0-9_.\\-/]+)");
    private static final Pattern NUMBER_METRIC_PATTERN = Pattern.compile("\\b(\\d+(?:\\.\\d+)?(?:k|ms|s|m|%|lines|tokens|dp|px|sp)?)\\b");
    private static final Pattern BOLD_PATTERN = Pattern.compile("\\*\\*([^*]+)\\*\\*");

    /**
     * Formats terminal text for mobile display:
     * - Trims oversized box-drawing divider lines (137 dashes) down to targetColumns so they never blow out screen width
     * - Strips trailing whitespace on every line so lines that terminate with '...' have ZERO phantom empty space after them
     * - Collapses huge internal whitespace gaps (used by desktop TUIs to right-align items) so footers fit on phone
     */
    public static String formatTerminalText(String text, boolean wrapMode, int targetColumns) {
        if (text == null || text.isEmpty()) return "";

        int maxCols = targetColumns > 20 ? targetColumns : 45;
        StringBuilder sb = new StringBuilder();
        String[] lines = text.split("\n", -1);

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];

            // Always strip trailing whitespace: eliminates the vast empty space after '...' or short lines
            line = line.replaceAll("\\s+$", "");

            String trimmed = line.trim();

            // 1. Divider line adaptation: fit line width to avoid 4-line wrapped borders or 137-col horizontal sprawl
            if (DIVIDER_PATTERN.matcher(trimmed).matches()) {
                char ch = trimmed.charAt(0);
                StringBuilder div = new StringBuilder();
                int count = Math.min(maxCols, 80);
                for (int c = 0; c < count; c++) {
                    div.append(ch);
                }
                line = div.toString();
            } else if (line.length() > 20) {
                // 2. Collapse internal runs of 6+ spaces in the middle of a line (e.g. desktop TUI status footers)
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

            sb.append(line);
            if (i < lines.length - 1) {
                sb.append("\n");
            }
        }
        return sb.toString();
    }

    /**
     * Colorizes terminal text into a rich Spannable using vibrant electric colors:
     * - Parses ANSI escape codes if present
     * - Applies full semantic syntax highlighting for active tools, thinking blocks, responses, lists, prompts, and footers
     */
    public static CharSequence colorize(String text, boolean wrapMode, int targetColumns) {
        if (text == null || text.isEmpty()) return "";

        String formatted = formatTerminalText(text, wrapMode, targetColumns);

        if (formatted.contains("\u001B[")) {
            return parseAnsiColors(formatted);
        }

        return parseSemanticColors(formatted);
    }

    private static CharSequence parseAnsiColors(String text) {
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
                        try {
                            int c256 = Integer.parseInt(codes[i + 2]);
                            currentFg = get256Color(c256);
                            i += 2;
                        } catch (Exception ignored) {}
                    } else if (code == 38 && i + 4 < codes.length && "2".equals(codes[i + 1])) {
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
            case 0: return bright ? COLOR_MUTED : COLOR_DIVIDER;
            case 1: return bright ? COLOR_RED : Color.parseColor("#EF4444");
            case 2: return bright ? COLOR_BRIGHT_GREEN : COLOR_GREEN;
            case 3: return bright ? COLOR_YELLOW : COLOR_AMBER;
            case 4: return bright ? COLOR_CYAN : Color.parseColor("#3B82F6");
            case 5: return bright ? COLOR_PURPLE : COLOR_VIOLET;
            case 6: return bright ? COLOR_LIGHT_CYAN : COLOR_CYAN;
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
     * Semantic syntax highlighting for plain-text terminal output (Konsole getAllDisplayedText).
     * Works both when terminal is active (tools/spinners) and when idle (assistant response, markdown, lists, prompts).
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

        // 2. Active Tool / Agent calls: ● Bash(...), ● Read(...), etc.
        Matcher toolM = TOOL_PATTERN.matcher(trimmed);
        if (toolM.matches()) {
            int relCircle = line.indexOf(trimmed.charAt(0));
            if (relCircle >= 0) {
                ssb.setSpan(new ForegroundColorSpan(COLOR_CYAN), s + relCircle, s + relCircle + 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                ssb.setSpan(new StyleSpan(Typeface.BOLD), s + relCircle, s + relCircle + 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);

                String toolName = toolM.group(1);
                int relTool = line.indexOf(toolName, relCircle + 1);
                if (relTool >= 0) {
                    ssb.setSpan(new ForegroundColorSpan(COLOR_WHITE), s + relTool, s + relTool + toolName.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                    ssb.setSpan(new StyleSpan(Typeface.BOLD), s + relTool, s + relTool + toolName.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);

                    int relArgs = relTool + toolName.length();
                    ssb.setSpan(new ForegroundColorSpan(COLOR_LIGHT_CYAN), s + relArgs, e, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);

                    int relHint = line.indexOf("(ctrl+o", relArgs);
                    if (relHint >= 0) {
                        ssb.setSpan(new ForegroundColorSpan(COLOR_MUTED), s + relHint, e, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                    }
                    return;
                }
            }
        }

        // 3. Response summary bullet line: ● The handoff is in ...
        if (trimmed.startsWith("●") || trimmed.startsWith("○")) {
            int relCircle = line.indexOf(trimmed.charAt(0));
            if (relCircle >= 0) {
                ssb.setSpan(new ForegroundColorSpan(COLOR_PURPLE), s + relCircle, s + relCircle + 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                ssb.setSpan(new StyleSpan(Typeface.BOLD), s + relCircle, s + relCircle + 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            ssb.setSpan(new ForegroundColorSpan(COLOR_DEFAULT), s + relCircle + 1, e, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            highlightPathsAndMetrics(ssb, line, s, relCircle + 1, line.length());
            return;
        }

        // 4. Thinking block: ▸ Thought for ...
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
                ssb.setSpan(new ForegroundColorSpan(COLOR_YELLOW), s + relLabel + 11, e, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            return;
        }

        // 5. Active Spinner: ⣻ Running command...
        Matcher spinnerM = SPINNER_PATTERN.matcher(trimmed);
        if (spinnerM.matches()) {
            int relSpin = line.indexOf(trimmed.charAt(0));
            if (relSpin >= 0) {
                ssb.setSpan(new ForegroundColorSpan(COLOR_AMBER), s + relSpin, s + relSpin + 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                ssb.setSpan(new ForegroundColorSpan(COLOR_YELLOW), s + relSpin + 1, e, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            return;
        }

        // 6. Work Metric line: ✻ Worked for 59s · done 11:46 am
        if (trimmed.startsWith("✻") || trimmed.contains("Worked for")) {
            int relIcon = line.indexOf("✻");
            if (relIcon >= 0) {
                ssb.setSpan(new ForegroundColorSpan(COLOR_PURPLE), s + relIcon, s + relIcon + 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            ssb.setSpan(new ForegroundColorSpan(COLOR_VIOLET), s, e, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            highlightPathsAndMetrics(ssb, line, s, 0, line.length());
            return;
        }

        // 7. Checkmarks: ✔ Update installed · Restart to update
        if (trimmed.contains("✔") || trimmed.contains("✓")) {
            int relCheck = Math.max(line.indexOf("✔"), line.indexOf("✓"));
            if (relCheck >= 0) {
                ssb.setSpan(new ForegroundColorSpan(COLOR_BRIGHT_GREEN), s + relCheck, s + relCheck + 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                ssb.setSpan(new StyleSpan(Typeface.BOLD), s + relCheck, s + relCheck + 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                ssb.setSpan(new ForegroundColorSpan(COLOR_BRIGHT_GREEN), s + relCheck + 1, e, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            return;
        }

        // 8. Crossmarks / failures
        if (trimmed.contains("✖") || trimmed.contains("✗")) {
            ssb.setSpan(new ForegroundColorSpan(COLOR_RED), s, e, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            return;
        }

        // 9. Status footers: ⏵⏵ auto mode on ...
        if (trimmed.contains("⏵")) {
            ssb.setSpan(new ForegroundColorSpan(COLOR_CYAN), s, e, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            ssb.setSpan(new StyleSpan(Typeface.BOLD), s, e, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            return;
        }

        // 10. Footers: esc to cancel ... Gemini 3.8 Flash · high
        if (trimmed.contains("esc to cancel")) {
            int relEsc = line.indexOf("esc to cancel");
            if (relEsc >= 0) {
                ssb.setSpan(new ForegroundColorSpan(COLOR_MUTED), s, s + relEsc + 13, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            int relModel = Math.max(line.indexOf("Gemini"), Math.max(line.indexOf("claude"), line.indexOf("gpt")));
            if (relModel >= 0) {
                ssb.setSpan(new ForegroundColorSpan(COLOR_GREEN), s + relModel, e, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                ssb.setSpan(new StyleSpan(Typeface.BOLD), s + relModel, e, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            return;
        }

        // 11. Shell prompts: user@host:path$ cmd
        Matcher shellM = SHELL_PROMPT_PATTERN.matcher(trimmed);
        if (shellM.matches()) {
            String promptPart = shellM.group(1);
            int relPrompt = line.indexOf(promptPart);
            if (relPrompt >= 0) {
                ssb.setSpan(new ForegroundColorSpan(COLOR_GREEN), s + relPrompt, s + relPrompt + promptPart.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                ssb.setSpan(new StyleSpan(Typeface.BOLD), s + relPrompt, s + relPrompt + promptPart.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                ssb.setSpan(new ForegroundColorSpan(COLOR_WHITE), s + relPrompt + promptPart.length(), e, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                ssb.setSpan(new StyleSpan(Typeface.BOLD), s + relPrompt + promptPart.length(), e, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                return;
            }
        }

        // 12. Prompt symbols: > cmd or ❯ cmd or $ cmd
        Matcher promptM = PROMPT_PATTERN.matcher(trimmed);
        if (promptM.matches()) {
            int relSym = line.indexOf(trimmed.charAt(0));
            if (relSym >= 0) {
                ssb.setSpan(new ForegroundColorSpan(COLOR_CYAN), s + relSym, s + relSym + 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                ssb.setSpan(new StyleSpan(Typeface.BOLD), s + relSym, s + relSym + 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                ssb.setSpan(new ForegroundColorSpan(COLOR_WHITE), s + relSym + 1, e, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                ssb.setSpan(new StyleSpan(Typeface.BOLD), s + relSym + 1, e, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                return;
            }
        }

        // 13. Bullet lists with labels: - The project: ... or • Deliverables: ...
        Matcher listM = LIST_ITEM_PATTERN.matcher(line);
        if (listM.matches()) {
            String bullet = listM.group(1);
            String label = listM.group(2);
            int relBullet = line.indexOf(bullet);
            int relLabel = relBullet + bullet.length();
            int relRest = relLabel + label.length();

            // Bullet in Purple
            ssb.setSpan(new ForegroundColorSpan(COLOR_PURPLE), s + relBullet, s + relLabel, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            // Label in Vibrant Gold Bold
            ssb.setSpan(new ForegroundColorSpan(COLOR_YELLOW), s + relLabel, s + relRest, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            ssb.setSpan(new StyleSpan(Typeface.BOLD), s + relLabel, s + relRest, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            // Rest of text in Crisp Off-White
            ssb.setSpan(new ForegroundColorSpan(COLOR_DEFAULT), s + relRest, e, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            highlightPathsAndMetrics(ssb, line, s, relRest, line.length());
            return;
        }

        // 14. Numbered lists with labels: 1. Task Overview: ...
        Matcher numListM = NUMBERED_LIST_PATTERN.matcher(line);
        if (numListM.matches()) {
            String num = numListM.group(1);
            String label = numListM.group(2);
            int relNum = line.indexOf(num);
            int relLabel = relNum + num.length();
            int relRest = relLabel + label.length();

            // Number in Purple
            ssb.setSpan(new ForegroundColorSpan(COLOR_PURPLE), s + relNum, s + relLabel, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            // Label in Vibrant Gold Bold
            ssb.setSpan(new ForegroundColorSpan(COLOR_YELLOW), s + relLabel, s + relRest, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            ssb.setSpan(new StyleSpan(Typeface.BOLD), s + relLabel, s + relRest, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            // Rest in Crisp Off-White
            ssb.setSpan(new ForegroundColorSpan(COLOR_DEFAULT), s + relRest, e, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            highlightPathsAndMetrics(ssb, line, s, relRest, line.length());
            return;
        }

        // 15. Markdown Headers: ### 3. Build & Deployment
        if (MD_HEADING_PATTERN.matcher(trimmed).matches()) {
            ssb.setSpan(new ForegroundColorSpan(COLOR_VIOLET), s, e, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            ssb.setSpan(new StyleSpan(Typeface.BOLD), s, e, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            return;
        }

        // 16. Label Headers: User Requests:
        if (LABEL_HEADING_PATTERN.matcher(trimmed).matches()) {
            ssb.setSpan(new ForegroundColorSpan(COLOR_VIOLET), s, e, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            ssb.setSpan(new StyleSpan(Typeface.BOLD), s, e, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            return;
        }

        // 17. Errors & Exceptions
        String lower = trimmed.toLowerCase();
        if (lower.startsWith("error:") || lower.startsWith("fatal:") || lower.contains("exception") || lower.contains("traceback (most recent") || lower.startsWith("failed")) {
            ssb.setSpan(new ForegroundColorSpan(COLOR_RED), s, e, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            return;
        }

        // 18. Warnings
        if (lower.startsWith("warning:") || lower.startsWith("warn:")) {
            ssb.setSpan(new ForegroundColorSpan(COLOR_YELLOW), s, e, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            return;
        }

        // 19. Success
        if (lower.startsWith("success") || lower.contains("successfully") || lower.equals("ok") || lower.startsWith("passed")) {
            ssb.setSpan(new ForegroundColorSpan(COLOR_BRIGHT_GREEN), s, e, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            return;
        }

        // 20. Default regular text (idle / assistant response text)
        ssb.setSpan(new ForegroundColorSpan(COLOR_DEFAULT), s, e, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        highlightPathsAndMetrics(ssb, line, s, 0, line.length());
    }

    private static void highlightPathsAndMetrics(SpannableStringBuilder ssb, String line, int lineStartInSsb, int minCol, int maxCol) {
        // Track ranges of paths to prevent number regex from overwriting path segments
        List<int[]> pathRanges = new ArrayList<>();

        // Highlight file paths, code tokens, and URLs in Electric Cyan
        Matcher pathM = PATH_OR_CODE_PATTERN.matcher(line);
        while (pathM.find()) {
            int colStart = pathM.start();
            int colEnd = pathM.end();
            if (colStart >= minCol && colEnd <= maxCol) {
                int pStart = lineStartInSsb + colStart;
                int pEnd = lineStartInSsb + colEnd;
                ssb.setSpan(new ForegroundColorSpan(COLOR_CYAN), pStart, pEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                pathRanges.add(new int[]{colStart, colEnd});
            }
        }

        // Highlight durations, numbers, token metrics in Vibrant Amber/Gold
        Matcher numM = NUMBER_METRIC_PATTERN.matcher(line);
        while (numM.find()) {
            int colStart = numM.start();
            int colEnd = numM.end();
            if (colStart >= minCol && colEnd <= maxCol) {
                boolean overlaps = false;
                for (int[] range : pathRanges) {
                    if (colStart < range[1] && colEnd > range[0]) {
                        overlaps = true;
                        break;
                    }
                }
                if (!overlaps) {
                    int nStart = lineStartInSsb + colStart;
                    int nEnd = lineStartInSsb + colEnd;
                    ssb.setSpan(new ForegroundColorSpan(COLOR_YELLOW), nStart, nEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
            }
        }

        // Highlight markdown bold **bold text** in Pure White Bold
        Matcher boldM = BOLD_PATTERN.matcher(line);
        while (boldM.find()) {
            int colStart = boldM.start(1);
            int colEnd = boldM.end(1);
            if (colStart >= minCol && colEnd <= maxCol) {
                int bStart = lineStartInSsb + colStart;
                int bEnd = lineStartInSsb + colEnd;
                ssb.setSpan(new ForegroundColorSpan(COLOR_WHITE), bStart, bEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                ssb.setSpan(new StyleSpan(Typeface.BOLD), bStart, bEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }
    }
}
