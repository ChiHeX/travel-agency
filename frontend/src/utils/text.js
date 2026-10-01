/**
 * 文本长度口径工具。
 *
 * 契约（JSON Schema 的 `maxLength` / `minLength`）数的是**字符**，也就是 Unicode 码点；
 * 后端的 `@CodePointLength` 与库内列宽（utf8mb4 下 `VARCHAR(N)` 就是 N 个字符）都是同一口径。
 * 而 JS 的 `String#length` 与 HTML 的 `maxlength` 数的是 **UTF-16 码元**：一个 emoji 是
 * 1 个码点却是 2 个码元，只有汉字等 BMP 字符下两者才等价。
 *
 * 于是此前会出现两种错：64 个 emoji 的姓名（契约允许、库内 `VARCHAR(64)` 存得下）被页面
 * 判成超长；更糟的是输入框上的 `maxlength="64"` 会在打字过程中把它静默截断成 32 个 emoji，
 * 用户连提示都看不到。
 *
 * 约定：页面侧一律用 {@link codePointLength} 校验，并且**不给文本输入框设 `maxlength`**，
 * 改为「不截断 + 实时码点计数 + 提交时校验」。唯一例外是账号名这类由正则限定为 ASCII 的
 * 字段，其码点与码元恒等。
 */

/** 字符数（Unicode 码点）：`'😀'` 记 1，`'ab'` 记 2，`null` / `undefined` 记 0。 */
export const codePointLength = (value) => [...String(value ?? '')].length

/**
 * UTF-8 字节数。
 *
 * 注意它与码点数单位不同：BCrypt 的硬上限是 **72 字节**，25 个汉字只有 25 个码点却占 75 字节，
 * 所以密码字段要同时看这两个口径（见 `PasswordRules`、契约 API.md §4.2）。
 */
export const utf8Bytes = (value) => new TextEncoder().encode(String(value ?? '')).length

/** 是否超过码点上限。模板里的计数器与提交前校验共用它，避免同一个判断写两遍。 */
export const overCodePoints = (value, max) => codePointLength(value) > max
