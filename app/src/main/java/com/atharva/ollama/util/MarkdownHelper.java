package com.atharva.ollama.util;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.style.BackgroundColorSpan;
import android.text.style.ClickableSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.text.style.TypefaceSpan;
import android.view.View;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;

import com.atharva.ollama.R;

import org.commonmark.node.FencedCodeBlock;
import org.commonmark.node.IndentedCodeBlock;

import io.noties.markwon.AbstractMarkwonPlugin;
import io.noties.markwon.Markwon;
import io.noties.markwon.MarkwonSpansFactory;
import io.noties.markwon.MarkwonVisitor;
import io.noties.markwon.core.MarkwonTheme;
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin;
import io.noties.markwon.ext.tables.TablePlugin;
import io.noties.markwon.ext.tasklist.TaskListPlugin;
import io.noties.markwon.html.HtmlPlugin;
import io.noties.markwon.linkify.LinkifyPlugin;

/**
 * Helper class for creating a fully-configured Markwon instance
 * with syntax highlighting, tables, and other markdown features.
 */
public class MarkdownHelper {

    /**
     * Create a Markwon instance with all plugins configured.
     *
     * @param context   The application context
     * @param isDarkMode Whether dark mode is enabled
     * @return Configured Markwon instance
     */
    public static Markwon createMarkwon(Context context, boolean isDarkMode) {
        return Markwon.builder(context)
                // Strikethrough support (~~text~~)
                .usePlugin(StrikethroughPlugin.create())
                // Table support
                .usePlugin(TablePlugin.create(context))
                // Linkify (auto-detect URLs)
                .usePlugin(LinkifyPlugin.create())
                // Task list support (- [ ] item)
                .usePlugin(TaskListPlugin.create(context))
                // HTML support
                .usePlugin(HtmlPlugin.create())
                // Custom code block styling
                .usePlugin(createCodeBlockPlugin(context, isDarkMode))
                // Theme customization
                .usePlugin(createThemePlugin(context, isDarkMode))
                .build();
    }

    /**
     * Create a plugin for custom code block styling with syntax highlighting colors.
     */
    private static AbstractMarkwonPlugin createCodeBlockPlugin(Context context, boolean isDarkMode) {
        return new AbstractMarkwonPlugin() {
            @Override
            public void configureVisitor(@NonNull MarkwonVisitor.Builder builder) {
                // Handle fenced code blocks (```code```)
                builder.on(FencedCodeBlock.class, (visitor, node) -> {
                    String code = node.getLiteral();
                    String language = node.getInfo();
                    
                    SpannableStringBuilder codeSpan = createStyledCodeBlock(context, code, language, isDarkMode);
                    
                    visitor.builder().append('\n');
                    visitor.builder().append(codeSpan);
                    visitor.builder().append('\n');
                });

                // Handle indented code blocks
                builder.on(IndentedCodeBlock.class, (visitor, node) -> {
                    String code = node.getLiteral();
                    
                    SpannableStringBuilder codeSpan = createStyledCodeBlock(context, code, "", isDarkMode);
                    
                    visitor.builder().append('\n');
                    visitor.builder().append(codeSpan);
                    visitor.builder().append('\n');
                });
            }
        };
    }

    /**
     * Create a styled code block with background, monospace font, and copy button.
     */
    private static SpannableStringBuilder createStyledCodeBlock(Context context, String code, String language, boolean isDarkMode) {
        SpannableStringBuilder builder = new SpannableStringBuilder();
        
        // Trim the code for copying
        String trimmedCode = code;
        if (trimmedCode.endsWith("\n")) {
            trimmedCode = trimmedCode.substring(0, trimmedCode.length() - 1);
        }
        final String codeToCopy = trimmedCode;
        
        // Add language header with copy button
        int headerStart = 0;
        if (language != null && !language.isEmpty()) {
            builder.append(language.toUpperCase());
            builder.setSpan(
                new ForegroundColorSpan(isDarkMode ? Color.parseColor("#888888") : Color.parseColor("#666666")),
                0, language.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            );
            builder.setSpan(
                new StyleSpan(Typeface.BOLD),
                0, language.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            );
            builder.append("  ");
        }
        
        // Add clickable copy button
        int copyStart = builder.length();
        builder.append("COPY"); // Simple text, no emoji
        int copyEnd = builder.length();
        
        builder.setSpan(new ClickableSpan() {
            @Override
            public void onClick(@NonNull View widget) {
                copyToClipboard(context, codeToCopy);
            }

            @Override
            public void updateDrawState(@NonNull TextPaint ds) {
                ds.setColor(isDarkMode ? Color.parseColor("#6CB6FF") : Color.parseColor("#0969DA"));
                ds.setUnderlineText(false);
                ds.setTypeface(Typeface.DEFAULT_BOLD);
            }
        }, copyStart, copyEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        
        builder.append("\n");
        
        int codeStart = builder.length();
        builder.append(codeToCopy);
        int codeEnd = builder.length();
        
        // Apply syntax highlighting based on language
        if (language != null && !language.isEmpty()) {
            applySyntaxHighlighting(builder, codeStart, codeEnd, language, isDarkMode);
        }
        
        // Apply background color to entire block
        int bgColor = isDarkMode ? Color.parseColor("#1E1E1E") : Color.parseColor("#F5F5F5");
        builder.setSpan(
            new BackgroundColorSpan(bgColor),
            0, builder.length(),
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        );
        
        // Apply monospace font to code only (not header)
        builder.setSpan(
            new TypefaceSpan("monospace"),
            codeStart, codeEnd,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        );
        
        // Apply code text color
        int textColor = isDarkMode ? Color.parseColor("#D4D4D4") : Color.parseColor("#1F1F1F");
        builder.setSpan(
            new ForegroundColorSpan(textColor),
            codeStart, codeEnd,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        );
        
        return builder;
    }
    
    /**
     * Copy text to clipboard and show toast.
     */
    private static void copyToClipboard(Context context, String text) {
        ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        ClipData clip = ClipData.newPlainText("Code", text);
        if (clipboard != null) {
            clipboard.setPrimaryClip(clip);
            Toast.makeText(context, "Code copied to clipboard", Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Apply basic syntax highlighting based on language.
     */
    private static void applySyntaxHighlighting(SpannableStringBuilder builder, int start, int end, String language, boolean isDarkMode) {
        String code = builder.subSequence(start, end).toString();
        
        // Define colors based on theme
        int keywordColor = isDarkMode ? Color.parseColor("#569CD6") : Color.parseColor("#0000FF");
        int stringColor = isDarkMode ? Color.parseColor("#CE9178") : Color.parseColor("#A31515");
        int commentColor = isDarkMode ? Color.parseColor("#6A9955") : Color.parseColor("#008000");
        int numberColor = isDarkMode ? Color.parseColor("#B5CEA8") : Color.parseColor("#098658");
        int functionColor = isDarkMode ? Color.parseColor("#DCDCAA") : Color.parseColor("#795E26");
        
        language = language.toLowerCase();
        
        // Common keywords for various languages
        String[] keywords;
        switch (language) {
            case "java":
            case "kotlin":
                keywords = new String[]{"public", "private", "protected", "class", "interface", "extends", "implements", 
                    "static", "final", "void", "int", "long", "double", "float", "boolean", "char", "byte", "short",
                    "if", "else", "for", "while", "do", "switch", "case", "break", "continue", "return", "try", 
                    "catch", "finally", "throw", "throws", "new", "this", "super", "null", "true", "false",
                    "import", "package", "fun", "val", "var", "override", "suspend", "object", "companion"};
                break;
            case "python":
            case "py":
                keywords = new String[]{"def", "class", "if", "elif", "else", "for", "while", "try", "except", 
                    "finally", "with", "as", "import", "from", "return", "yield", "lambda", "pass", "break", 
                    "continue", "True", "False", "None", "and", "or", "not", "in", "is", "async", "await", "self"};
                break;
            case "javascript":
            case "js":
            case "typescript":
            case "ts":
                keywords = new String[]{"function", "const", "let", "var", "if", "else", "for", "while", "do",
                    "switch", "case", "break", "continue", "return", "try", "catch", "finally", "throw",
                    "new", "this", "class", "extends", "import", "export", "default", "async", "await",
                    "true", "false", "null", "undefined", "typeof", "instanceof", "=>", "interface", "type"};
                break;
            case "c":
            case "cpp":
            case "c++":
                keywords = new String[]{"int", "long", "short", "char", "float", "double", "void", "bool",
                    "if", "else", "for", "while", "do", "switch", "case", "break", "continue", "return",
                    "struct", "class", "public", "private", "protected", "virtual", "override", "const",
                    "static", "extern", "typedef", "sizeof", "new", "delete", "true", "false", "NULL", "nullptr",
                    "#include", "#define", "#ifdef", "#ifndef", "#endif", "template", "typename", "namespace", "using"};
                break;
            case "swift":
                keywords = new String[]{"func", "var", "let", "class", "struct", "enum", "protocol", "extension",
                    "if", "else", "guard", "switch", "case", "for", "while", "repeat", "break", "continue",
                    "return", "throw", "try", "catch", "import", "public", "private", "internal", "fileprivate",
                    "static", "override", "final", "lazy", "weak", "strong", "nil", "true", "false", "self", "Self"};
                break;
            case "go":
            case "golang":
                keywords = new String[]{"func", "var", "const", "type", "struct", "interface", "map", "chan",
                    "if", "else", "for", "range", "switch", "case", "default", "break", "continue", "return",
                    "go", "defer", "select", "package", "import", "true", "false", "nil", "make", "new", "append"};
                break;
            case "rust":
                keywords = new String[]{"fn", "let", "mut", "const", "static", "struct", "enum", "trait", "impl",
                    "if", "else", "match", "for", "while", "loop", "break", "continue", "return", "use", "mod",
                    "pub", "self", "Self", "super", "crate", "async", "await", "move", "ref", "true", "false", "None", "Some"};
                break;
            case "sql":
                keywords = new String[]{"SELECT", "FROM", "WHERE", "AND", "OR", "NOT", "INSERT", "INTO", "VALUES",
                    "UPDATE", "SET", "DELETE", "CREATE", "TABLE", "DROP", "ALTER", "INDEX", "JOIN", "LEFT", "RIGHT",
                    "INNER", "OUTER", "ON", "GROUP", "BY", "ORDER", "ASC", "DESC", "LIMIT", "OFFSET", "NULL",
                    "PRIMARY", "KEY", "FOREIGN", "REFERENCES", "UNIQUE", "DEFAULT", "CHECK", "CONSTRAINT"};
                break;
            case "bash":
            case "sh":
            case "shell":
                keywords = new String[]{"if", "then", "else", "elif", "fi", "for", "while", "do", "done", "case",
                    "esac", "function", "return", "exit", "echo", "export", "source", "alias", "unset", "local",
                    "readonly", "shift", "break", "continue", "true", "false", "cd", "pwd", "ls", "mkdir", "rm", "cp", "mv"};
                break;
            default:
                // Generic keywords for unknown languages
                keywords = new String[]{"if", "else", "for", "while", "return", "function", "class", "def", 
                    "true", "false", "null", "nil", "var", "let", "const", "public", "private", "import"};
        }
        
        // Highlight keywords
        for (String keyword : keywords) {
            highlightPattern(builder, start, code, "\\b" + keyword + "\\b", keywordColor);
        }
        
        // Highlight strings (both single and double quoted)
        highlightPattern(builder, start, code, "\"[^\"\\\\]*(\\\\.[^\"\\\\]*)*\"", stringColor);
        highlightPattern(builder, start, code, "'[^'\\\\]*(\\\\.[^'\\\\]*)*'", stringColor);
        
        // Highlight numbers
        highlightPattern(builder, start, code, "\\b\\d+(\\.\\d+)?\\b", numberColor);
        
        // Highlight comments (// and /* */ and #)
        highlightPattern(builder, start, code, "//.*", commentColor);
        highlightPattern(builder, start, code, "#.*", commentColor);
        highlightPattern(builder, start, code, "/\\*[\\s\\S]*?\\*/", commentColor);
    }

    /**
     * Highlight all matches of a regex pattern in the code.
     */
    private static void highlightPattern(SpannableStringBuilder builder, int offset, String code, String pattern, int color) {
        try {
            java.util.regex.Pattern p = java.util.regex.Pattern.compile(pattern);
            java.util.regex.Matcher m = p.matcher(code);
            while (m.find()) {
                int start = offset + m.start();
                int end = offset + m.end();
                if (start >= 0 && end <= builder.length() && start < end) {
                    builder.setSpan(
                        new ForegroundColorSpan(color),
                        start, end,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    );
                }
            }
        } catch (Exception e) {
            // Ignore regex errors
        }
    }

    /**
     * Create a theme plugin for customizing Markwon appearance.
     */
    private static AbstractMarkwonPlugin createThemePlugin(Context context, boolean isDarkMode) {
        return new AbstractMarkwonPlugin() {
            @Override
            public void configureTheme(@NonNull MarkwonTheme.Builder builder) {
                // Inline code styling
                int inlineCodeBg = isDarkMode ? Color.parseColor("#2D2D2D") : Color.parseColor("#E8E8E8");
                int inlineCodeText = isDarkMode ? Color.parseColor("#CE9178") : Color.parseColor("#C7254E");
                
                builder.codeBackgroundColor(inlineCodeBg)
                       .codeTextColor(inlineCodeText)
                       .codeTypeface(Typeface.MONOSPACE)
                       .codeTextSize(14);
                
                // Heading styling
                builder.headingBreakHeight(0);
                
                // Blockquote styling
                int quoteColor = isDarkMode ? Color.parseColor("#555555") : Color.parseColor("#CCCCCC");
                builder.blockQuoteColor(quoteColor);
                
                // Link styling
                int linkColor = isDarkMode ? Color.parseColor("#6CB6FF") : Color.parseColor("#0969DA");
                builder.linkColor(linkColor);
                
                // Thematic break (hr)
                int thematicBreakColor = isDarkMode ? Color.parseColor("#444444") : Color.parseColor("#DDDDDD");
                builder.thematicBreakColor(thematicBreakColor);
            }
        };
    }
}
