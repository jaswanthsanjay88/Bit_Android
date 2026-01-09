package com.atharva.ollama.util;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ReasoningHelper {

    private static final Pattern THINK_PATTERN = Pattern.compile("<think>(.*?)</think>", Pattern.DOTALL);

    public static class ParseResult {
        public String thinking;
        public String finalAnswer;

        public ParseResult(String thinking, String finalAnswer) {
            this.thinking = thinking;
            this.finalAnswer = finalAnswer;
        }
    }

    public static ParseResult parse(String content) {
        if (content == null) return new ParseResult(null, "");
        
        Matcher matcher = THINK_PATTERN.matcher(content);
        if (matcher.find()) {
            String thinking = matcher.group(1).trim();
            String finalAnswer = matcher.replaceAll("").trim();
            return new ParseResult(thinking, finalAnswer);
        }
        
        return new ParseResult(null, content);
    }
}
