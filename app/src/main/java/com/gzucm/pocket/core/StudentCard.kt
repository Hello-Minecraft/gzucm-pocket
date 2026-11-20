package com.gzucm.pocket.core

import kotlinx.serialization.Serializable

/**
 * 学籍卡片上的几项。
 *
 * 2026-11-20 之前一直以为教务拿不到班级 —— 课表接口只给 `jxbmc`（教学班名），
 * 那个东西一门课合好几个班，格式还不统一，拿它当班级一定出错。
 * 但用 CDP 打开「信息查询 → 学籍卡片」才发现，**学籍页里有一行正正经经的「班级」**，
 * 而且是服务端渲染的，只要同步课表那个 cookie 就能拿到（见 `Jw.fetchStudentCard`）。
 *
 * 有了它，班级作业就不用让每个同学手敲班级名了 —— 敲错一个字符就找不到列表。
 */
@Serializable
data class StudentCard(
    /** 行政班，如「一院23中医(铁涛)5」。这是唯一可靠的班级标识 */
    val className: String,
    /** 所在年级，如「2023」 */
    val grade: String = "",
    /** 专业，如「中医学(铁涛班)」 */
    val major: String = "",
    /** 院系，如「第一临床医学院」 */
    val college: String = "",
    val studentId: String = "",
) {
    /**
     * 给「班级作业」用的默认班级名。
     *
     * 直接用 [className]：它本身已经带了年级和方向（「一院23中医(铁涛)5」），
     * 同校不同班不会撞。真正的唯一性由教务保证，不需要我们再拼。
     */
    val classKey: String get() = className
}

/**
 * 从学籍卡片的表格里取一个字段。
 *
 * 页面结构（2026-11-20 在真机上抓下来的原文）：
 *
 * ```html
 * <td align="right">班级：</td>
 * <td align="left"><label style="width: 90px">一院23中医(铁涛)5</label></td>
 * ```
 *
 * 单独抽出来是为了**能被单测钉住** —— 正则这东西，线上抓一次看不出来对不对，
 * 一旦教务改了模板（多一个属性、换个标签）就会静默返回 null，
 * 表现是「班级作业怎么不预填了」，很难联想到是这里。
 */
internal fun parseStudentField(html: String, name: String): String? =
    // 前面那个 `>` 是必需的：页面里还有「教学班级」「培养层次」这类词，
    // 只匹配「班级：」会取到「教学班级：」后面那个值（不存在的字段会静默返回 null，
    // 这种错最难查）。要求字段名紧跟在标签结束之后，就等于要求它是独立的一格。
    Regex(""">\s*${Regex.escape(name)}[：:]\s*</td>\s*<td[^>]*>\s*<label[^>]*>([^<]*)""")
        .find(html)
        ?.groupValues?.get(1)
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
