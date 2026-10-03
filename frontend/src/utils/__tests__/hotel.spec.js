// @vitest-environment jsdom
import { describe, expect, it } from 'vitest'
import {
  ACCOMMODATION_TYPES,
  FACILITY_LABELS,
  FACILITY_VALUES,
  accommodationLabel,
  accommodationSummary,
  accommodationTypeOf,
  breakfastLabel,
  checkInLabel,
  checkOutLabel,
  facilityLabel,
  starRatingLabel
} from '../hotel'

/**
 * 住宿 / 酒店展示口径的单元测试。
 *
 * <p>这里钉住的是三个"看起来只是文案、实际会影响用户判断"的规则：</p>
 * <ol>
 *   <li><b>未知枚举不落屏</b>：`HotelFacility` 是封闭枚举，将来新增取值时旧前端不认识的取值
 *       必须整项跳过，而不是把 `SOME_NEW_ENUM` 直接印在用户端；</li>
 *   <li><b>`breakfastIncluded` 是三态</b>：`false` 是"不含早餐"，`null` 是"尚未说明" ——
 *       把未说明渲染成"不含早餐"就是在替酒店改承诺；</li>
 *   <li><b>`hotelId` 为空不等于不含住宿</b>：只有 `accommodationType = NONE` 才是不含住宿，
 *       缺失字段兜底时沿用服务端迁移口径（有酒店 → HOTEL，没有 → PENDING），不推断成 NONE。</li>
 * </ol>
 */
describe('utils/hotel 设施标签', () => {
  it('覆盖契约 HotelFacility 的全部 14 个取值，且中文文案与契约一致', () => {
    expect(FACILITY_VALUES).toHaveLength(14)
    expect(FACILITY_LABELS).toMatchObject({
      WIFI: 'Wi-Fi',
      PARKING: '停车场',
      RESTAURANT: '餐厅',
      BREAKFAST_SERVICE: '早餐服务',
      AIR_CONDITIONING: '空调',
      FRONT_DESK_24H: '24 小时前台',
      LUGGAGE_STORAGE: '行李寄存',
      ELEVATOR: '电梯',
      LAUNDRY: '洗衣服务',
      GYM: '健身房',
      SWIMMING_POOL: '游泳池',
      AIRPORT_SHUTTLE: '接送机',
      NON_SMOKING_ROOM: '无烟房',
      ACCESSIBLE_FACILITIES: '无障碍设施'
    })
    for (const value of FACILITY_VALUES) expect(facilityLabel(value)).toBe(FACILITY_LABELS[value])
  })

  it('未知 / 非法取值返回 null，调用方跳过而不是把枚举原文印出来', () => {
    expect(facilityLabel('SAUNA')).toBeNull()
    expect(facilityLabel('')).toBeNull()
    expect(facilityLabel(null)).toBeNull()
    expect(facilityLabel(undefined)).toBeNull()
    expect(facilityLabel({ WIFI: true })).toBeNull()
  })
})

describe('utils/hotel 早餐三态', () => {
  it('true → 含早餐，false → 不含早餐，null / undefined → 不显示', () => {
    expect(breakfastLabel(true)).toBe('含早餐')
    expect(breakfastLabel(false)).toBe('不含早餐')
    expect(breakfastLabel(null)).toBeNull()
    expect(breakfastLabel(undefined)).toBeNull()
  })

  it('false 与 null 不能混为一谈：未说明不会被渲染成"不含早餐"', () => {
    expect(breakfastLabel(null)).not.toBe(breakfastLabel(false))
  })
})

describe('utils/hotel 住宿安排类型', () => {
  it('四种类型各有中文标签，未知取值返回 null', () => {
    expect(accommodationLabel('HOTEL')).toBe('指定酒店')
    expect(accommodationLabel('STANDARD')).toBe('只确定住宿标准')
    expect(accommodationLabel('NONE')).toBe('当天不含住宿')
    expect(accommodationLabel('PENDING')).toBe('住宿待确认')
    expect(accommodationLabel('SOMETHING_ELSE')).toBeNull()
    expect(ACCOMMODATION_TYPES).toEqual(['HOTEL', 'STANDARD', 'NONE', 'PENDING'])
  })

  it('字段缺失时按服务端迁移口径兜底：有酒店 → HOTEL，没有 → PENDING（不推断成 NONE）', () => {
    expect(accommodationTypeOf({ accommodationType: 'STANDARD' })).toBe('STANDARD')
    expect(accommodationTypeOf({ hotelId: '31' })).toBe('HOTEL')
    expect(accommodationTypeOf({ hotelName: '演示酒店' })).toBe('HOTEL')
    expect(accommodationTypeOf({})).toBe('PENDING')
    expect(accommodationTypeOf(null)).toBe('PENDING')
    // 明确的 NONE 才是不含住宿
    expect(accommodationTypeOf({ accommodationType: 'NONE' })).toBe('NONE')
  })

  it('折叠标题的住宿摘要：有酒店名用酒店名，否则按类型给说明', () => {
    expect(accommodationSummary({ hotelName: '杭州湖畔演示酒店', accommodationType: 'HOTEL' })).toBe('杭州湖畔演示酒店')
    expect(accommodationSummary({ accommodationType: 'NONE' })).toBe('当天不含住宿')
    expect(accommodationSummary({ accommodationType: 'STANDARD' })).toBe('只确定住宿标准')
    expect(accommodationSummary({ accommodationType: 'PENDING' })).toBe('住宿待确认')
    expect(accommodationSummary({})).toBe('住宿待确认')
    // 名称是空白串时不算有名称
    expect(accommodationSummary({ hotelName: '   ', accommodationType: 'NONE' })).toBe('当天不含住宿')
  })
})

describe('utils/hotel 官方星级与入住退房时间', () => {
  it('星级只在是 1～5 的整数时给出文案，字符串与越界值一律不显示', () => {
    expect(starRatingLabel(4)).toBe('4 星')
    expect(starRatingLabel(1)).toBe('1 星')
    expect(starRatingLabel(5)).toBe('5 星')
    // 契约里 starRating 是 integer 或 null：字符串 '4'、0、6、小数都不是合法取值
    expect(starRatingLabel('4')).toBeNull()
    expect(starRatingLabel(0)).toBeNull()
    expect(starRatingLabel(6)).toBeNull()
    expect(starRatingLabel(4.5)).toBeNull()
    expect(starRatingLabel(null)).toBeNull()
    expect(starRatingLabel(undefined)).toBeNull()
  })

  it('入住 / 退房只输出时刻本身，未提供时返回 null', () => {
    expect(checkInLabel('14:00')).toBe('14:00 起')
    expect(checkOutLabel('12:00')).toBe('12:00 前')
    expect(checkInLabel(null)).toBeNull()
    expect(checkOutLabel('')).toBeNull()
    expect(checkOutLabel(undefined)).toBeNull()
  })
})
