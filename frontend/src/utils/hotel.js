/** BREAKFAST_SERVICE 表示酒店服务，不代表线路含早餐。 */
export const FACILITY_LABELS = {
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
}

export const FACILITY_VALUES = Object.keys(FACILITY_LABELS)

export function facilityLabel(value) {
  if (typeof value !== 'string') return null
  return FACILITY_LABELS[value] ?? null
}

// null 表示未说明，不能显示为不含早餐。
export function breakfastLabel(value) {
  if (value === true) return '含早餐'
  if (value === false) return '不含早餐'
  return null
}

export const ACCOMMODATION_TYPES = ['HOTEL', 'STANDARD', 'NONE', 'PENDING']

export function accommodationLabel(type) {
  if (type === 'HOTEL') return '指定酒店'
  if (type === 'STANDARD') return '只确定住宿标准'
  if (type === 'NONE') return '当天不含住宿'
  if (type === 'PENDING') return '住宿待确认'
  return null
}

// 兼容旧数据：无酒店按待确认处理，不推断为不含住宿。
export function accommodationTypeOf(day) {
  if (ACCOMMODATION_TYPES.includes(day?.accommodationType)) return day.accommodationType
  const hotelId = day?.hotelId
  if (hotelId !== null && hotelId !== undefined && String(hotelId) !== '') return 'HOTEL'
  if (day?.hotelName) return 'HOTEL'
  return 'PENDING'
}

export function accommodationSummary(day) {
  const name = typeof day?.hotelName === 'string' ? day.hotelName.trim() : ''
  if (name) return name
  return accommodationLabel(accommodationTypeOf(day)) || '住宿安排暂未提供'
}

export function starRatingLabel(value) {
  if (typeof value !== 'number' || !Number.isInteger(value)) return null
  if (value < 1 || value > 5) return null
  return `${value} 星`
}

export function checkInLabel(value) {
  const text = typeof value === 'string' ? value.trim() : ''
  return text ? `${text} 起` : null
}

export function checkOutLabel(value) {
  const text = typeof value === 'string' ? value.trim() : ''
  return text ? `${text} 前` : null
}
