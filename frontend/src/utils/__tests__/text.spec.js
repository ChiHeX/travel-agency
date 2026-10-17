import { describe, expect, it } from 'vitest'
import { codePointLength, overCodePoints, utf8Bytes } from '../text'

/**
 * 文本长度口径工具的测试。
 *
 * 背景：契约的 `maxLength` 与后端的 `@CodePointLength` 数码点，而 `String#length`
 * 与 HTML 的 `maxlength` 数 UTF-16 码元。一个 emoji 是 1 个码点却是 2 个码元，
 * 于是「契约允许、页面却判超长」或「输入框把它悄悄截短一半」。
 */
describe('codePointLength', () => {
  it('汉字与 ASCII 的码点数等于 String#length', () => {
    expect(codePointLength('大理古城')).toBe(4)
    expect(codePointLength('ab')).toBe(2)
  })

  it('emoji 记 1 个码点，而 String#length 记 2', () => {
    expect(codePointLength('😀')).toBe(1)
    expect('😀'.length).toBe(2)
    expect(codePointLength('😀'.repeat(64))).toBe(64)
    expect('😀'.repeat(64).length).toBe(128)
  })

  it('组合字符按码点算，不做字素簇归并（与 JSON Schema / 后端口径一致）', () => {
    // 'é' 的分解写法是 e + U+0301，是 2 个码点；契约与 @CodePointLength 同样数 2。
    expect(codePointLength('e\u0301')).toBe(2)
  })

  it('null / undefined / 数字等非字符串输入按 0 与字符串化处理，不抛异常', () => {
    expect(codePointLength(null)).toBe(0)
    expect(codePointLength(undefined)).toBe(0)
    expect(codePointLength('')).toBe(0)
    expect(codePointLength(12345)).toBe(5)
  })
})

describe('utf8Bytes', () => {
  it('ASCII 1 字节、汉字 3 字节、emoji 4 字节', () => {
    expect(utf8Bytes('a'.repeat(72))).toBe(72)
    expect(utf8Bytes('汉'.repeat(24))).toBe(72)
    expect(utf8Bytes('汉'.repeat(25))).toBe(75)
    expect(utf8Bytes('😀')).toBe(4)
  })

  it('空值与 null 记 0 字节', () => {
    expect(utf8Bytes('')).toBe(0)
    expect(utf8Bytes(null)).toBe(0)
  })
})

describe('overCodePoints', () => {
  it('恰好到上限不算超，超过一个码点才算超', () => {
    expect(overCodePoints('😀'.repeat(64), 64)).toBe(false)
    expect(overCodePoints('😀'.repeat(65), 64)).toBe(true)
    // 同一段文本按码元判断会误判：128 个码元 > 64
    expect('😀'.repeat(64).length > 64).toBe(true)
  })
})
