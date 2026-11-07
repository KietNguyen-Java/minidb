package com.kietnguyen.minidb.sql;

import com.kietnguyen.minidb.SqlException;

import java.util.ArrayList;
import java.util.List;

/** Turns SQL text into tokens. Keywords are returned as identifiers; the parser decides. */
public final class Lexer {

    public enum Kind { IDENT, NUMBER, STRING, SYMBOL, EOF }

    public record Token(Kind kind, String text, int position) {
        public boolean isKeyword(String keyword) {
            return kind == Kind.IDENT && text.equalsIgnoreCase(keyword);
        }

        public boolean isSymbol(String symbol) {
            return kind == Kind.SYMBOL && text.equals(symbol);
        }

        @Override
        public String toString() {
            return kind == Kind.EOF ? "end of input" : "'" + text + "'";
        }
    }

    private static final List<String> TWO_CHAR_SYMBOLS = List.of("<=", ">=", "!=", "<>");
    private static final String ONE_CHAR_SYMBOLS = "(),;*=<>+-/.";

    private Lexer() {
    }

    public static List<Token> tokenize(String sql) {
        List<Token> tokens = new ArrayList<>();
        int i = 0;
        int n = sql.length();
        while (i < n) {
            char c = sql.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
            } else if (c == '-' && i + 1 < n && sql.charAt(i + 1) == '-') {
                while (i < n && sql.charAt(i) != '\n') {
                    i++; // line comment
                }
            } else if (Character.isLetter(c) || c == '_') {
                int start = i;
                while (i < n && (Character.isLetterOrDigit(sql.charAt(i)) || sql.charAt(i) == '_')) {
                    i++;
                }
                tokens.add(new Token(Kind.IDENT, sql.substring(start, i), start));
            } else if (Character.isDigit(c)) {
                int start = i;
                while (i < n && Character.isDigit(sql.charAt(i))) {
                    i++;
                }
                tokens.add(new Token(Kind.NUMBER, sql.substring(start, i), start));
            } else if (c == '\'') {
                int start = i++;
                StringBuilder text = new StringBuilder();
                while (true) {
                    if (i >= n) {
                        throw new SqlException("Unterminated string starting at position " + start);
                    }
                    char ch = sql.charAt(i++);
                    if (ch == '\'') {
                        if (i < n && sql.charAt(i) == '\'') {
                            text.append('\''); // '' is an escaped quote
                            i++;
                        } else {
                            break;
                        }
                    } else {
                        text.append(ch);
                    }
                }
                tokens.add(new Token(Kind.STRING, text.toString(), start));
            } else if (i + 1 < n && TWO_CHAR_SYMBOLS.contains(sql.substring(i, i + 2))) {
                String symbol = sql.substring(i, i + 2);
                tokens.add(new Token(Kind.SYMBOL, symbol.equals("<>") ? "!=" : symbol, i));
                i += 2;
            } else if (ONE_CHAR_SYMBOLS.indexOf(c) >= 0) {
                tokens.add(new Token(Kind.SYMBOL, String.valueOf(c), i));
                i++;
            } else {
                throw new SqlException("Unexpected character '" + c + "' at position " + i);
            }
        }
        tokens.add(new Token(Kind.EOF, "", n));
        return tokens;
    }
}
