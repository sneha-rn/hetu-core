/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.prestosql.type;

import io.airlift.jcodings.specific.NonStrictUTF8Encoding;
import io.airlift.joni.Option;
import io.airlift.joni.Regex;
import io.airlift.joni.Syntax;
import io.airlift.slice.DynamicSliceOutput;
import io.airlift.slice.Slice;
import io.prestosql.spi.PrestoException;
import io.prestosql.spi.function.LiteralParameter;
import io.prestosql.spi.function.LiteralParameters;
import io.prestosql.spi.function.OperatorType;
import io.prestosql.spi.function.ScalarFunction;
import io.prestosql.spi.function.ScalarOperator;
import io.prestosql.spi.function.SqlType;
import io.prestosql.spi.type.LikePatternType;
import io.prestosql.spi.type.StandardTypes;

import java.util.Optional;

import static io.airlift.joni.constants.MetaChar.INEFFECTIVE_META_CHAR;
import static io.airlift.joni.constants.SyntaxProperties.OP_ASTERISK_ZERO_INF;
import static io.airlift.joni.constants.SyntaxProperties.OP_DOT_ANYCHAR;
import static io.airlift.joni.constants.SyntaxProperties.OP_LINE_ANCHOR;
import static io.airlift.slice.SliceUtf8.getCodePointAt;
import static io.airlift.slice.SliceUtf8.lengthOfCodePoint;
import static io.prestosql.spi.StandardErrorCode.INVALID_FUNCTION_ARGUMENT;
import static io.prestosql.spi.type.Chars.padSpaces;
import static io.prestosql.util.Failures.checkCondition;
import static java.nio.charset.StandardCharsets.UTF_8;

public final class LikeFunctions
{
    private static final Syntax SYNTAX = new Syntax(
            OP_DOT_ANYCHAR | OP_ASTERISK_ZERO_INF | OP_LINE_ANCHOR,
            0,
            0,
            Option.NONE,
            new Syntax.MetaCharTable(
                    '\\',                           /* esc */
                    INEFFECTIVE_META_CHAR,          /* anychar '.' */
                    INEFFECTIVE_META_CHAR,          /* anytime '*' */
                    INEFFECTIVE_META_CHAR,          /* zero or one time '?' */
                    INEFFECTIVE_META_CHAR,          /* one or more time '+' */
                    INEFFECTIVE_META_CHAR));        /* anychar anytime */

    private LikeFunctions() {}

    @ScalarFunction(value = "like", hidden = true)
    @LiteralParameters("x")
    @SqlType(StandardTypes.BOOLEAN)
    public static boolean likeChar(@LiteralParameter("x") Long x, @SqlType("char(x)") Slice value, @SqlType(LikePatternType.NAME) Regex pattern)
    {
        return likeVarchar(padSpaces(value, x.intValue()), pattern);
    }

    // TODO: this should not be callable from SQL
    @ScalarFunction(value = "like", hidden = true)
    @LiteralParameters("x")
    @SqlType(StandardTypes.BOOLEAN)
    public static boolean likeVarchar(@SqlType("varchar(x)") Slice value, @SqlType(LikePatternType.NAME) Regex pattern)
    {
        // Joni can infinite loop with UTF8Encoding when invalid UTF-8 is encountered.
        // NonStrictUTF8Encoding must be used to avoid this issue.
        byte[] bytes = value.getBytes();
        return regexMatches(pattern, bytes);
    }

    @ScalarOperator(OperatorType.CAST)
    @LiteralParameters("x")
    @SqlType(LikePatternType.NAME)
    public static Regex castVarcharToLikePattern(@SqlType("varchar(x)") Slice pattern)
    {
        return likePattern(pattern);
    }

    @ScalarOperator(OperatorType.CAST)
    @LiteralParameters("x")
    @SqlType(LikePatternType.NAME)
    public static Regex castCharToLikePattern(@LiteralParameter("x") Long charLength, @SqlType("char(x)") Slice pattern)
    {
        return likePattern(padSpaces(pattern, charLength.intValue()));
    }

    public static Regex likePattern(Slice pattern)
    {
        return likePattern(pattern.toStringUtf8(), '0', false);
    }

    @ScalarFunction
    @LiteralParameters({"x", "y"})
    @SqlType(LikePatternType.NAME)
    public static Regex likePattern(@SqlType("varchar(x)") Slice pattern, @SqlType("varchar(y)") Slice escape)
    {
        return likePattern(pattern.toStringUtf8(), getEscapeChar(escape), true);
    }

    public static boolean isLikePattern(Slice pattern, Optional<Slice> escape)
    {
        return patternConstantPrefixBytes(pattern, escape) < pattern.length();
    }

    public static int patternConstantPrefixBytes(Slice pattern, Optional<Slice> escape)
    {
        int escapeChar = getEscapeCharacter(escape)
                .map(c -> (int) c)
                .orElse(-1);

        boolean escaped = false;
        int position = 0;
        while (position < pattern.length()) {
            int currentChar = getCodePointAt(pattern, position);
            if (!escaped && (currentChar == escapeChar)) {
                escaped = true;
            }
            else if (escaped) {
                checkEscape(currentChar == '%' || currentChar == '_' || currentChar == escapeChar);
                escaped = false;
            }
            else if ((currentChar == '%') || (currentChar == '_')) {
                return position;
            }
            position += lengthOfCodePoint(currentChar);
        }
        checkEscape(!escaped);
        return position;
    }

    public static Slice unescapeLiteralLikePattern(Slice pattern, Optional<Slice> escape)
    {
        if (!escape.isPresent()) {
            return pattern;
        }

        int escapeChar = getEscapeCharacter(escape)
                .map(c -> (int) c)
                .orElse(-1);

        @SuppressWarnings("resource")
        DynamicSliceOutput output = new DynamicSliceOutput(pattern.length());
        boolean escaped = false;
        int position = 0;
        while (position < pattern.length()) {
            int currentChar = getCodePointAt(pattern, position);
            int lengthOfCodePoint = lengthOfCodePoint(currentChar);
            if (!escaped && (currentChar == escapeChar)) {
                escaped = true;
            }
            else {
                output.writeBytes(pattern, position, lengthOfCodePoint);
                escaped = false;
            }
            position += lengthOfCodePoint;
        }
        checkEscape(!escaped);
        return output.slice();
    }

    private static Optional<Character> getEscapeCharacter(Optional<Slice> escape)
    {
        if (!escape.isPresent()) {
            return Optional.empty();
        }
        String stringEscape = escape.get().toStringUtf8();
        // non-BMP escape is not supported
        checkCondition(stringEscape.length() == 1, INVALID_FUNCTION_ARGUMENT, "Escape string must be a single character");
        return Optional.of(stringEscape.charAt(0));
    }

    private static void checkEscape(boolean condition)
    {
        checkCondition(condition, INVALID_FUNCTION_ARGUMENT, "Escape character must be followed by '%%', '_' or the escape character itself");
    }

    private static boolean regexMatches(Regex regex, byte[] bytes)
    {
        return regex.matcher(bytes).match(0, bytes.length, Option.NONE) != -1;
    }

    @SuppressWarnings("NestedSwitchStatement")
    private static Regex likePattern(String patternString, char escapeChar, boolean shouldEscape)
    {
        StringBuilder regex = new StringBuilder(patternString.length() * 2);

        regex.append('^');
        boolean escaped = false;
        for (char currentChar : patternString.toCharArray()) {
            checkEscape(!escaped || currentChar == '%' || currentChar == '_' || currentChar == escapeChar);
            if (shouldEscape && !escaped && (currentChar == escapeChar)) {
                escaped = true;
            }
            else {
                switch (currentChar) {
                    case '%':
                        regex.append(escaped ? "%" : ".*");
                        escaped = false;
                        break;
                    case '_':
                        regex.append(escaped ? "_" : ".");
                        escaped = false;
                        break;
                    default:
                        // escape special regex characters
                        switch (currentChar) {
                            case '\\':
                            case '^':
                            case '$':
                            case '.':
                            case '*':
                                regex.append('\\');
                        }

                        regex.append(currentChar);
                        escaped = false;
                }
            }
        }
        checkEscape(!escaped);
        regex.append('$');

        byte[] bytes = regex.toString().getBytes(UTF_8);
        return new Regex(bytes, 0, bytes.length, Option.MULTILINE, NonStrictUTF8Encoding.INSTANCE, SYNTAX);
    }

    @SuppressWarnings("NumericCastThatLosesPrecision")
    private static char getEscapeChar(Slice escape)
    {
        String escapeString = escape.toStringUtf8();
        if (escapeString.isEmpty()) {
            // escaping disabled
            return (char) -1; // invalid character
        }
        if (escapeString.length() == 1) {
            return escapeString.charAt(0);
        }
        throw new PrestoException(INVALID_FUNCTION_ARGUMENT, "Escape string must be a single character");
    }
}
