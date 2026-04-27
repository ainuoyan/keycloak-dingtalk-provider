package com.ainuoyan.keycloak.dingtalk;

import java.util.Locale;
import net.sourceforge.pinyin4j.PinyinHelper;
import net.sourceforge.pinyin4j.format.HanyuPinyinCaseType;
import net.sourceforge.pinyin4j.format.HanyuPinyinOutputFormat;
import net.sourceforge.pinyin4j.format.HanyuPinyinToneType;
import net.sourceforge.pinyin4j.format.HanyuPinyinVCharType;
import org.apache.commons.lang3.StringUtils;

final class PinyinUsername {

    private static final HanyuPinyinOutputFormat PINYIN_FORMAT = new HanyuPinyinOutputFormat();

    static {
        PINYIN_FORMAT.setCaseType(HanyuPinyinCaseType.LOWERCASE);
        PINYIN_FORMAT.setToneType(HanyuPinyinToneType.WITHOUT_TONE);
        PINYIN_FORMAT.setVCharType(HanyuPinyinVCharType.WITH_V);
    }

    private PinyinUsername() {
    }

    static String fromChineseName(String name) {
        if (StringUtils.isBlank(name)) {
            return null;
        }

        StringBuilder username = new StringBuilder();
        for (int i = 0; i < name.length(); i++) {
            char ch = name.charAt(i);
            if (Character.isWhitespace(ch)) {
                continue;
            }
            if (isAsciiLetterOrDigit(ch)) {
                username.append(Character.toLowerCase(ch));
                continue;
            }

            String pinyin = toPinyin(ch);
            if (StringUtils.isNotBlank(pinyin)) {
                username.append(pinyin);
            }
        }

        return StringUtils.trimToNull(username.toString());
    }

    private static String toPinyin(char ch) {
        try {
            String[] candidates = PinyinHelper.toHanyuPinyinStringArray(ch, PINYIN_FORMAT);
            if (candidates == null || candidates.length == 0) {
                return null;
            }
            return candidates[0].toLowerCase(Locale.ROOT);
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean isAsciiLetterOrDigit(char ch) {
        return (ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z') || (ch >= '0' && ch <= '9');
    }
}
