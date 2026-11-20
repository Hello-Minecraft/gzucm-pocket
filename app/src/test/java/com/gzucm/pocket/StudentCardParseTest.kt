package com.gzucm.pocket

import com.gzucm.pocket.core.parseStudentField
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 学籍卡片 HTML 的字段解析。
 *
 * 用的样本是 2026-11-20 在真机上用 CDP 抓下来的原文（去掉无关行），
 * 包括 `\r\n` 和 `\t` —— 教务那套模板用 EasyUI 渲染，缩进很重，
 * 正则里少写一个 `\s*` 就会在真机上匹配不到，而单测里用干净的字符串测不出来。
 */
class StudentCardParseTest {

    private val realSnippet = "<td align=\"right\">学号：</td>\r\n" +
        "\t\t\t\t<td align=\"left\"><label style=\"width: 90px\">2023014093</label></td>\r\n" +
        "\t\t\t\t<td align=\"right\">姓名：</td>\r\n" +
        "\t\t\t\t<td align=\"left\"><label style=\"width: 90px\">朱致辉</label></td>\r\n" +
        "\t\t\t\t<td align=\"right\">入学年份：</td>\r\n" +
        "\t\t\t\t<td align=\"left\"><label style=\"width: 90px\">2023</label></td>\r\n" +
        "\t\t\t</tr>\r\n" +
        "\t\t\t<tr>\r\n" +
        "\t\t\t\t<td align=\"right\">院系名称：</td>\r\n" +
        "\t\t\t\t<td align=\"left\"><label style=\"width: 90px\">第一临床医学院</label></td>\r\n" +
        "\t\t\t\t<td align=\"right\">专业：</td>\r\n" +
        "\t\t\t\t<td align=\"left\"><label style=\"width: 90px\">中医学(铁涛班)</label></td>\r\n" +
        "\t\t\t\t<td align=\"right\">培养层次：</td>\r\n" +
        "\t\t\t\t<td align=\"left\"><label style=\"width: 90px\">本科</label></td>\r\n" +
        "\t\t\t</tr>\r\n" +
        "\t\t\t<tr>\r\n" +
        "\t\t\t\t<td align=\"right\">班级：</td>\r\n" +
        "\t\t\t\t<td align=\"left\"><label style=\"width: 90px\">一院23中医(铁涛)5</label></td>\r\n" +
        "\t\t\t\t<td align=\"right\">所在年级：</td>\r\n" +
        "\t\t\t\t<td align=\"left\"><label style=\"width: 90px\">2023</label></td>\r\n" +
        "\t\t\t\t<td align=\"right\">学制：</td>\r\n" +
        "\t\t\t\t<td align=\"left\"><label style=\"width: 90px\">5.0</label></td>\r\n"

    @Test
    fun `真机样本里能取到班级`() {
        assertEquals("一院23中医(铁涛)5", parseStudentField(realSnippet, "班级"))
    }

    @Test
    fun `班级不会和所在年级搞混`() {
        val grade = parseStudentField(realSnippet, "所在年级")
        assertEquals("2023", grade)
        assertEquals(
            "这两个字段在同一行里，取错一个就会让班级作业落到别的班",
            "一院23中医(铁涛)5",
            parseStudentField(realSnippet, "班级"),
        )
    }

    @Test
    fun `专业和院系也取得到`() {
        assertEquals("中医学(铁涛班)", parseStudentField(realSnippet, "专业"))
        assertEquals("第一临床医学院", parseStudentField(realSnippet, "院系名称"))
        assertEquals("2023014093", parseStudentField(realSnippet, "学号"))
    }

    /**
     * 页面上还有「教学班级」这种词。如果正则不要求字段名紧跟标签结束，
     * 取「班级」就会命中「教学班级：」，拿到别人的班 —— 而且不会报错。
     */
    @Test
    fun `教学班级不会被当成班级`() {
        val tricky = "<td align=\"right\">教学班级：</td>" +
            "<td align=\"left\"><label>别的班</label></td>" +
            "<td align=\"right\">班级：</td>" +
            "<td align=\"left\"><label>一院23中医(铁涛)5</label></td>"
        assertEquals("一院23中医(铁涛)5", parseStudentField(tricky, "班级"))
    }

    @Test
    fun `字段为空时返回 null 而不是空串`() {
        val empty = "<td align=\"right\">班级：</td><td align=\"left\"><label></label></td>"
        assertNull(parseStudentField(empty, "班级"))
    }

    @Test
    fun `没有这个字段时返回 null`() {
        assertNull(parseStudentField(realSnippet, "宿舍"))
    }
}
