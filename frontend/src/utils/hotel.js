/**
 * 住宿展示的共用口径（用户端线路每日行程 + 酒店详情页）。
 *
 * 这里只做**枚举到中文**和**空值到"不显示"**的映射，不含任何默认值、评分或设施补充：
 * 契约里没有的数据一律不编（`docs/DEVELOPMENT_GUIDE.md` §1「禁止用死数据代替正式功能」、
 * §4「禁止为了页面看起来完整自动生成虚假的…统计数据」）。
 *
 * 三条最容易出错的规则固化在这里，避免两个页面各写一遍后走偏：
 *
 * 1. **未知枚举不展示原文**：`HotelFacility` 是封闭枚举（将来可能新增取值），
 *    遇到当前版本不认识的取值要跳过整个标签，而不是把 `SOME_NEW_ENUM` 直接印在页面上
 *    （见 {@link facilityLabel} 返回 `null` 的约定）；
 * 2. **`breakfastIncluded` 是三态**：`true` 含、`false` **不含**、`null` 尚未说明。
 *    `null` 与 `false` 不是一回事，未说明时界面不显示这一项，而不是写成"不含早餐"；
 * 3. **`hotelId` 为空不等于不含住宿**：只有 `accommodationType = NONE` 才是"当天不含住宿"，
 *    没有关联酒店也可能是"只确定住宿标准"（`STANDARD`）或"还没确认"（`PENDING`）。
 */

/**
 * 契约 `HotelFacility` 枚举 → 中文标签。
 *
 * 注意 `BREAKFAST_SERVICE` 是"**酒店自身提供早餐服务**"，与当天行程的 `breakfastIncluded`
 * （本线路当天住宿是否含早餐）互不推断，两者都不应被用来解释对方。
 */
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

/** 契约 `HotelFacility` 的全部取值，供后台多选限定在枚举内。 */
export const FACILITY_VALUES = Object.keys(FACILITY_LABELS)

/**
 * 设施标签文案；**未知取值返回 `null`**，调用方应跳过而不是渲染原始枚举名。
 *
 * @param {unknown} value 契约 `HotelFacility` 取值
 * @returns {string|null}
 */
export function facilityLabel(value) {
  if (typeof value !== 'string') return null
  return FACILITY_LABELS[value] ?? null
}

/**
 * 当天的住宿是否含早餐（契约 `ItineraryDay.breakfastIncluded`，三态）。
 *
 * @param {unknown} value `true` / `false` / `null`（或缺失）
 * @returns {string|null} `'含早餐'` / `'不含早餐'` / `null`（null 表示尚未说明，界面不显示这一项）
 */
export function breakfastLabel(value) {
  if (value === true) return '含早餐'
  if (value === false) return '不含早餐'
  return null
}

/** 契约 `AccommodationType` 的全部取值。 */
export const ACCOMMODATION_TYPES = ['HOTEL', 'STANDARD', 'NONE', 'PENDING']

/**
 * 当天的住宿安排类型（契约 `AccommodationType`）→ 中文标签。
 *
 * 未知取值返回 `null`（不把枚举原文抛给用户），由调用方回退到 {@link accommodationSummary}。
 *
 * @param {unknown} type
 * @returns {string|null}
 */
export function accommodationLabel(type) {
  if (type === 'HOTEL') return '指定酒店'
  if (type === 'STANDARD') return '只确定住宿标准'
  if (type === 'NONE') return '当天不含住宿'
  if (type === 'PENDING') return '住宿待确认'
  return null
}

/**
 * 归一化某一天的住宿类型。
 *
 * 正常响应的 `accommodationType` 是必填字段，这里只为"字段缺失或取值不认识"的老数据兜底，
 * 且**沿用服务端迁移脚本的口径**（`sql/migrations/010-add-hotel-accommodation.sql`）：
 * 有酒店 → `HOTEL`，没有酒店 → `PENDING`，**任何情况下都不推断成 `NONE`**。
 * 把"没填酒店"当成"不含住宿"会把一个尚在确认的行程说成没有住宿，比留白更糟。
 *
 * @param {object|null|undefined} day 契约 `ItineraryDay`
 * @returns {'HOTEL'|'STANDARD'|'NONE'|'PENDING'}
 */
export function accommodationTypeOf(day) {
  if (ACCOMMODATION_TYPES.includes(day?.accommodationType)) return day.accommodationType
  const hotelId = day?.hotelId
  if (hotelId !== null && hotelId !== undefined && String(hotelId) !== '') return 'HOTEL'
  if (day?.hotelName) return 'HOTEL'
  return 'PENDING'
}

/**
 * 每日行程折叠标题里的住宿摘要（`hotelName · <住宿安排>` 的后半句）。
 *
 * 指定酒店时用行程自身记录的 `hotelName`（酒店之后被停用也不会被改写）；
 * 其余类型按 `accommodationType` 给出说明 —— `NONE` 因此读作"当天不含住宿"，
 * 而不是笼统的"住宿安排暂未提供"。
 *
 * @param {object|null|undefined} day 契约 `ItineraryDay`
 * @returns {string}
 */
export function accommodationSummary(day) {
  const name = typeof day?.hotelName === 'string' ? day.hotelName.trim() : ''
  if (name) return name
  return accommodationLabel(accommodationTypeOf(day)) || '住宿安排暂未提供'
}

/**
 * 官方星级的展示文案（契约 `starRating`：1～5 的整数或 `null`）。
 *
 * **只在真的是数字时才给文案**：字符串 `'4'`、`null`、越界值一律返回 `null`，
 * 由调用方整行不渲染。星级是**官方星级**，没有可靠依据时契约要求为 `null`，
 * 不得用网站评分或"几钻"顶替（见 `docs/API.md` §12.2）。
 *
 * @param {unknown} value
 * @returns {string|null} 例如 `'4 星'`
 */
export function starRatingLabel(value) {
  if (typeof value !== 'number' || !Number.isInteger(value)) return null
  if (value < 1 || value > 5) return null
  return `${value} 星`
}

/**
 * 入住时间文案的后半句（契约 `ClockTime`，如 `14:00 起`，配合"入住"标签使用）；未提供时返回 `null`。
 *
 * 只负责时间本身，不重复"入住"二字：页面上的标签列已经写明"入住 / 退房"。
 */
export function checkInLabel(value) {
  const text = typeof value === 'string' ? value.trim() : ''
  return text ? `${text} 起` : null
}

/** 退房时间文案的后半句（契约 `ClockTime`，如 `12:00 前`）；未提供时返回 `null`。 */
export function checkOutLabel(value) {
  const text = typeof value === 'string' ? value.trim() : ''
  return text ? `${text} 前` : null
}
