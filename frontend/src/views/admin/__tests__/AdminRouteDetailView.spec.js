// @vitest-environment jsdom
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { ElMessage } from 'element-plus'
import AdminRouteDetailView from '../AdminRouteDetailView.vue'

const fetchRoute = vi.fn()
const fetchHotels = vi.fn()
const fetchAttractions = vi.fn()
const createItineraryDay = vi.fn()
const updateItineraryDay = vi.fn()

vi.mock('@/api/modules', () => ({
  adminApi: {
    route: (...args) => fetchRoute(...args),
    hotels: (...args) => fetchHotels(...args),
    attractions: (...args) => fetchAttractions(...args),
    createItineraryDay: (...args) => createItineraryDay(...args),
    updateItineraryDay: (...args) => updateItineraryDay(...args)
  }
}))

vi.mock('vue-router', () => ({
  useRoute: () => ({ params: { id: '21' } }),
  useRouter: () => ({ push: vi.fn(), back: vi.fn() })
}))

vi.mock('element-plus', () => ({
  ElMessage: { success: vi.fn(), warning: vi.fn(), error: vi.fn(), info: vi.fn() },
  ElMessageBox: { confirm: vi.fn().mockResolvedValue('confirm') }
}))

const activeHotel = { id: '31', name: '杭州湖畔演示酒店', status: 'ACTIVE' }
const disabledHotel = { id: '32', name: '已停用演示酒店', status: 'DISABLED' }

const dayOnDisabledHotel = {
  id: '71',
  dayNumber: 1,
  title: '上海 → 昆明',
  description: null,
  transportation: null,
  meals: null,
  hotelId: '32',
  hotelName: '已停用演示酒店',
  accommodationType: 'HOTEL',
  accommodationStandard: null,
  roomType: '双床房',
  breakfastIncluded: true,
  accommodationNote: null,
  items: []
}

const standardDay = {
  id: '72',
  dayNumber: 2,
  title: '大理',
  description: null,
  transportation: null,
  meals: null,
  hotelId: null,
  hotelName: null,
  accommodationType: 'STANDARD',
  accommodationStandard: '市区舒适型酒店',
  roomType: '大床房',
  breakfastIncluded: false,
  accommodationNote: '以出团通知为准',
  items: []
}

const pendingDay = {
  id: '73',
  dayNumber: 3,
  title: '昆明 · 大理',
  description: null,
  transportation: null,
  meals: null,
  hotelId: null,
  hotelName: null,
  accommodationType: 'PENDING',
  accommodationStandard: '市区舒适型酒店（具体酒店待定）',
  roomType: '双床房',
  breakfastIncluded: true,
  accommodationNote: '具体酒店以出团通知为准',
  items: []
}

function mockRouteDetail(itinerary = [dayOnDisabledHotel]) {
  return {
    route: { id: '21', name: '云南 6 日', status: 'DRAFT', durationDays: 6 },
    departures: [],
    itinerary,
    reviews: []
  }
}

function mountView() {
  return mount(AdminRouteDetailView, {
    global: {
      stubs: {
        'el-dialog': { template: '<div><slot /><slot name="footer" /></div>' },
        'el-skeleton': true,
        RouteFormDialog: true,
        PanelIconButton: true
      }
    }
  })
}

function buttonByText(wrapper, text) {
  const button = wrapper.findAll('button').find((item) => item.text().includes(text))
  if (!button) {
    throw new Error(`找不到按钮「${text}」，现有按钮：${wrapper.findAll('button').map((b) => b.text()).join(' / ')}`)
  }
  return button
}

function editDayButton(wrapper, index = 0) {
  const card = wrapper.findAll('.day-card')[index]
  return card.findAll('button').find((item) => item.text().trim() === '编辑')
}

function hotelSelect(wrapper) {
  const select = wrapper.findAll('select')
    .find((item) => item.findAll('option').some((option) => option.text() === '不指定'))
  if (!select) throw new Error('找不到酒店下拉')
  return select
}

function hotelOptions(wrapper) {
  return hotelSelect(wrapper).findAll('option').filter((option) => option.text() !== '不指定')
}

function accommodationSelect(wrapper) {
  const select = wrapper.findAll('select')
    .find((item) => item.findAll('option').some((option) => option.text() === '当天不含住宿'))
  if (!select) throw new Error('找不到住宿安排下拉')
  return select
}

function breakfastSelect(wrapper) {
  const select = breakfastSelects(wrapper)[0]
  if (!select) throw new Error('找不到早餐下拉')
  return select
}

function breakfastSelects(wrapper) {
  return wrapper.findAll('select')
    .filter((item) => item.findAll('option').some((option) => option.text() === '未说明'))
}

function standardInput(wrapper) {
  return wrapper.find('input[placeholder^="例如：市区舒适型酒店"]')
}

function roomTypeInput(wrapper) {
  return wrapper.find('input[placeholder="例如：双床房"]')
}

function noteInput(wrapper) {
  return wrapper.find('textarea[placeholder^="例如：拼房安排"]')
}

function dayTitleInput(wrapper) {
  return wrapper.find('input[placeholder="例如：上海 → 昆明"]')
}

beforeEach(() => {
  fetchRoute.mockReset().mockResolvedValue(mockRouteDetail())
  fetchAttractions.mockReset().mockResolvedValue({ items: [], total: 0, totalPages: 0 })
  fetchHotels.mockReset().mockImplementation(async ({ page }) => (page === 1
    ? { items: [activeHotel], total: 101, totalPages: 2 }
    : { items: [disabledHotel], total: 101, totalPages: 2 }))
  createItineraryDay.mockReset().mockResolvedValue({ id: '99' })
  updateItineraryDay.mockReset().mockResolvedValue({ id: '71' })
  ElMessage.success.mockClear()
  ElMessage.warning.mockClear()
  ElMessage.error.mockClear()
})

describe('AdminRouteDetailView（每日行程的酒店选择）', () => {
  it('候选项逐页取全量：酒店与景点都不只取第一页', async () => {
    const wrapper = mountView()
    await flushPromises()

    buttonByText(wrapper, '新增一日行程').trigger('click')
    await flushPromises()

    expect(fetchHotels).toHaveBeenNthCalledWith(1, { page: 1, size: 100 })
    expect(fetchHotels).toHaveBeenNthCalledWith(2, { page: 2, size: 100 })
    expect(fetchAttractions).toHaveBeenNthCalledWith(1, { page: 1, size: 100 })
    expect(hotelOptions(wrapper).map((o) => o.text())).toEqual([
      '杭州湖畔演示酒店',
      '已停用演示酒店（已停用）'
    ])
  })

  it('新增行程：停用酒店在下拉里被禁用并标注，启用酒店可选', async () => {
    const wrapper = mountView()
    await flushPromises()

    buttonByText(wrapper, '新增一日行程').trigger('click')
    await flushPromises()

    const [active, disabled] = hotelOptions(wrapper)
    expect(active.attributes('disabled')).toBeUndefined()
    expect(disabled.attributes('disabled')).toBeDefined()
  })

  it('编辑已安排在停用酒店上的行程：该酒店保持可选，不迫使运营先换酒店', async () => {
    const wrapper = mountView()
    await flushPromises()

    editDayButton(wrapper).trigger('click')
    await flushPromises()

    const options = hotelOptions(wrapper)
    expect(options).toHaveLength(2)
    const current = options.find((option) => option.text().includes('已停用演示酒店'))
    expect(current.attributes('disabled')).toBeUndefined()
    expect(options.find((option) => option.text().includes('杭州湖畔')).attributes('disabled')).toBeUndefined()
  })
})

describe('AdminRouteDetailView（行程文本按码点校验）', () => {
  it('200 个码点的 emoji 标题通过前端校验并原样提交（码元长度是 400，不是 200）', async () => {
    const wrapper = mountView()
    await flushPromises()

    buttonByText(wrapper, '新增一日行程').trigger('click')
    await flushPromises()

    const title = '😀'.repeat(200)
    expect(title.length).toBe(400)

    await dayTitleInput(wrapper).setValue(title)
    await buttonByText(wrapper, '保存行程').trigger('click')
    await flushPromises()

    expect(ElMessage.warning).not.toHaveBeenCalled()
    expect(createItineraryDay).toHaveBeenCalledTimes(1)
    expect(createItineraryDay).toHaveBeenCalledWith('21', expect.objectContaining({ title }))
  })

  it('201 个码点的 emoji 标题被前端拦下：给出提示且不发请求', async () => {
    const wrapper = mountView()
    await flushPromises()

    buttonByText(wrapper, '新增一日行程').trigger('click')
    await flushPromises()

    await dayTitleInput(wrapper).setValue('😀'.repeat(201))
    await buttonByText(wrapper, '保存行程').trigger('click')
    await flushPromises()

    expect(createItineraryDay).not.toHaveBeenCalled()
    expect(ElMessage.warning).toHaveBeenCalledWith('行程标题最多 200 个字符')
  })
})

describe('AdminRouteDetailView（每日行程的住宿安排）', () => {
  async function openNewDayDialog() {
    const wrapper = mountView()
    await flushPromises()
    await buttonByText(wrapper, '新增一日行程').trigger('click')
    await flushPromises()
    return wrapper
  }

  it('新增行程默认「住宿待确认」：不会推断成"不含住宿"，全部住宿字段都显式提交', async () => {
    const wrapper = await openNewDayDialog()

    expect(accommodationSelect(wrapper).element.value).toBe('PENDING')
    expect(hotelSelect(wrapper).element.value).toBe('')

    await dayTitleInput(wrapper).setValue('上海 → 昆明')
    await buttonByText(wrapper, '保存行程').trigger('click')
    await flushPromises()

    expect(createItineraryDay).toHaveBeenCalledTimes(1)
    expect(createItineraryDay.mock.calls[0][1]).toMatchObject({
      dayNumber: 2,
      title: '上海 → 昆明',
      hotelId: null,
      accommodationType: 'PENDING',
      accommodationStandard: null,
      roomType: null,
      breakfastIncluded: null,
      accommodationNote: null
    })
  })

  it('选中酒店即自动成为「指定酒店」，早餐选"不含"时提交 false（而不是 null）', async () => {
    const wrapper = await openNewDayDialog()

    await hotelSelect(wrapper).setValue('31')
    await flushPromises()
    expect(accommodationSelect(wrapper).element.value).toBe('HOTEL')

    await roomTypeInput(wrapper).setValue('双床房')
    await breakfastSelect(wrapper).setValue('false')
    await noteInput(wrapper).setValue('如遇满房将安排同级别酒店')
    await dayTitleInput(wrapper).setValue('杭州')
    await buttonByText(wrapper, '保存行程').trigger('click')
    await flushPromises()

    const payload = createItineraryDay.mock.calls[0][1]
    expect(payload).toMatchObject({
      hotelId: '31',
      accommodationType: 'HOTEL',
      roomType: '双床房',
      breakfastIncluded: false,
      accommodationNote: '如遇满房将安排同级别酒店'
    })
    expect(payload.breakfastIncluded).toBe(false)
  })

  it('早餐选「未说明」与选「不含早餐」是不同的提交结果', async () => {
    const wrapper = await openNewDayDialog()
    await hotelSelect(wrapper).setValue('31')
    await flushPromises()
    await dayTitleInput(wrapper).setValue('杭州')

    await breakfastSelect(wrapper).setValue('false')
    await buttonByText(wrapper, '保存行程').trigger('click')
    await flushPromises()
    expect(createItineraryDay.mock.calls[0][1].breakfastIncluded).toBe(false)

    await breakfastSelect(wrapper).setValue('')
    await buttonByText(wrapper, '保存行程').trigger('click')
    await flushPromises()
    expect(createItineraryDay.mock.calls[1][1].breakfastIncluded).toBeNull()
  })

  it('住宿安排为「指定酒店」但没选酒店时拦下，不发请求', async () => {
    const wrapper = await openNewDayDialog()

    await accommodationSelect(wrapper).setValue('HOTEL')
    await dayTitleInput(wrapper).setValue('杭州')
    await buttonByText(wrapper, '保存行程').trigger('click')
    await flushPromises()

    expect(createItineraryDay).not.toHaveBeenCalled()
    expect(ElMessage.warning).toHaveBeenCalledWith('住宿安排为「指定酒店」时，请选择具体酒店')
  })

  it('「只确定住宿标准」必须填住宿标准，且提交时清空酒店', async () => {
    const wrapper = await openNewDayDialog()

    await hotelSelect(wrapper).setValue('31')
    await flushPromises()
    await accommodationSelect(wrapper).setValue('STANDARD')
    await flushPromises()
    expect(hotelSelect(wrapper).element.value).toBe('')
    expect(standardInput(wrapper).exists()).toBe(true)

    await dayTitleInput(wrapper).setValue('大理')
    await buttonByText(wrapper, '保存行程').trigger('click')
    await flushPromises()
    expect(createItineraryDay).not.toHaveBeenCalled()
    expect(ElMessage.warning).toHaveBeenCalledWith('住宿安排为「只确定住宿标准」时，请填写住宿标准')

    await standardInput(wrapper).setValue('市区舒适型酒店')
    await buttonByText(wrapper, '保存行程').trigger('click')
    await flushPromises()

    expect(createItineraryDay).toHaveBeenCalledTimes(1)
    expect(createItineraryDay.mock.calls[0][1]).toMatchObject({
      hotelId: null,
      accommodationType: 'STANDARD',
      accommodationStandard: '市区舒适型酒店'
    })
  })

  it('改成「当天不含住宿」后：酒店被清空，房型与早餐不再提交（契约要求一并清空）', async () => {
    const wrapper = await openNewDayDialog()

    await hotelSelect(wrapper).setValue('31')
    await flushPromises()
    await roomTypeInput(wrapper).setValue('双床房')
    await breakfastSelect(wrapper).setValue('true')

    await accommodationSelect(wrapper).setValue('NONE')
    await flushPromises()

    expect(hotelSelect(wrapper).element.value).toBe('')
    expect(roomTypeInput(wrapper).exists()).toBe(false)
    expect(wrapper.findAll('select').some((select) => select.findAll('option').some((option) => option.text() === '未说明'))).toBe(false)

    await noteInput(wrapper).setValue('当晚夜车返程，不含住宿')
    await dayTitleInput(wrapper).setValue('夜车返程')
    await buttonByText(wrapper, '保存行程').trigger('click')
    await flushPromises()

    expect(createItineraryDay.mock.calls[0][1]).toMatchObject({
      hotelId: null,
      accommodationType: 'NONE',
      accommodationStandard: null,
      roomType: null,
      breakfastIncluded: null,
      accommodationNote: '当晚夜车返程，不含住宿'
    })
  })

  it('编辑已有的"指定酒店"行程：回填住宿安排与三态早餐，保存时原样提交同一家酒店', async () => {
    const wrapper = mountView()
    await flushPromises()

    await editDayButton(wrapper).trigger('click')
    await flushPromises()

    expect(accommodationSelect(wrapper).element.value).toBe('HOTEL')
    expect(hotelSelect(wrapper).element.value).toBe('32')
    expect(breakfastSelect(wrapper).element.value).toBe('true')
    expect(roomTypeInput(wrapper).element.value).toBe('双床房')

    await dayTitleInput(wrapper).setValue('上海 → 昆明（改）')
    await buttonByText(wrapper, '保存行程').trigger('click')
    await flushPromises()

    expect(updateItineraryDay).toHaveBeenCalledTimes(1)
    expect(updateItineraryDay.mock.calls[0][0]).toBe('71')
    expect(updateItineraryDay.mock.calls[0][1]).toMatchObject({
      title: '上海 → 昆明（改）',
      hotelId: '32',
      accommodationType: 'HOTEL',
      roomType: '双床房',
      breakfastIncluded: true
    })
  })

  it('编辑"只确定住宿标准"的行程：回填住宿标准，且 breakfastIncluded=false 不会被当成未说明', async () => {
    fetchRoute.mockResolvedValue(mockRouteDetail([dayOnDisabledHotel, standardDay]))
    const wrapper = mountView()
    await flushPromises()

    await editDayButton(wrapper, 1).trigger('click')
    await flushPromises()

    expect(accommodationSelect(wrapper).element.value).toBe('STANDARD')
    expect(standardInput(wrapper).element.value).toBe('市区舒适型酒店')
    expect(roomTypeInput(wrapper).element.value).toBe('大床房')
    expect(breakfastSelect(wrapper).element.value).toBe('false')
    expect(noteInput(wrapper).element.value).toBe('以出团通知为准')

    await buttonByText(wrapper, '保存行程').trigger('click')
    await flushPromises()

    const payload = updateItineraryDay.mock.calls[0][1]
    expect(payload.breakfastIncluded).toBe(false)
    expect(payload.hotelId).toBeNull()
  })

  it('编辑「住宿待确认」行程只改标题时：已填写的住宿标准/房型/早餐必须保留', async () => {
    fetchRoute.mockResolvedValue(mockRouteDetail([pendingDay]))
    const wrapper = mountView()
    await flushPromises()

    await editDayButton(wrapper).trigger('click')
    await flushPromises()

    expect(accommodationSelect(wrapper).element.value).toBe('PENDING')
    expect(standardInput(wrapper).element.value).toBe('市区舒适型酒店（具体酒店待定）')
    expect(roomTypeInput(wrapper).element.value).toBe('双床房')
    expect(breakfastSelect(wrapper).element.value).toBe('true')

    await dayTitleInput(wrapper).setValue('上海 → 昆明（只改标题）')
    await buttonByText(wrapper, '保存行程').trigger('click')
    await flushPromises()

    expect(updateItineraryDay).toHaveBeenCalledTimes(1)
    expect(updateItineraryDay.mock.calls[0][1]).toMatchObject({
      title: '上海 → 昆明（只改标题）',
      hotelId: null,
      accommodationType: 'PENDING',
      accommodationStandard: '市区舒适型酒店（具体酒店待定）',
      roomType: '双床房',
      breakfastIncluded: true,
      accommodationNote: '具体酒店以出团通知为准'
    })
  })

  it('「住宿待确认」也能新增填写住宿标准、房型与早餐（不是只有指定酒店才能填）', async () => {
    const wrapper = await openNewDayDialog()

    expect(accommodationSelect(wrapper).element.value).toBe('PENDING')
    await dayTitleInput(wrapper).setValue('大理')
    await standardInput(wrapper).setValue('市区舒适型酒店')
    await roomTypeInput(wrapper).setValue('大床房')
    await breakfastSelect(wrapper).setValue('false')
    await buttonByText(wrapper, '保存行程').trigger('click')
    await flushPromises()

    const payload = createItineraryDay.mock.calls[0][1]
    expect(payload).toMatchObject({
      accommodationType: 'PENDING',
      accommodationStandard: '市区舒适型酒店',
      roomType: '大床房'
    })
    expect(payload.breakfastIncluded).toBe(false)
    expect(payload.hotelId).toBeNull()
  })

  it('改成「当天不含住宿」才清空住宿标准、房型与早餐（NONE 是唯一清空的类型）', async () => {
    fetchRoute.mockResolvedValue(mockRouteDetail([pendingDay]))
    const wrapper = mountView()
    await flushPromises()

    await editDayButton(wrapper).trigger('click')
    await flushPromises()
    await accommodationSelect(wrapper).setValue('NONE')
    await flushPromises()

    expect(standardInput(wrapper).exists()).toBe(false)
    expect(roomTypeInput(wrapper).exists()).toBe(false)
    expect(breakfastSelects(wrapper)).toHaveLength(0)

    await buttonByText(wrapper, '保存行程').trigger('click')
    await flushPromises()

    expect(updateItineraryDay.mock.calls[0][1]).toMatchObject({
      accommodationType: 'NONE',
      hotelId: null,
      accommodationStandard: null,
      roomType: null,
      breakfastIncluded: null
    })
  })

  it('住宿文案的长度上限与契约一致：住宿标准 500 / 房型 100 / 说明 1000', async () => {
    const wrapper = await openNewDayDialog()
    await accommodationSelect(wrapper).setValue('STANDARD')
    await dayTitleInput(wrapper).setValue('大理')

    await standardInput(wrapper).setValue('标'.repeat(501))
    await buttonByText(wrapper, '保存行程').trigger('click')
    await flushPromises()
    expect(createItineraryDay).not.toHaveBeenCalled()
    expect(ElMessage.warning).toHaveBeenCalledWith('住宿标准最多 500 个字符')

    await standardInput(wrapper).setValue('市区舒适型酒店')
    await roomTypeInput(wrapper).setValue('房'.repeat(101))
    await buttonByText(wrapper, '保存行程').trigger('click')
    await flushPromises()
    expect(createItineraryDay).not.toHaveBeenCalled()
    expect(ElMessage.warning).toHaveBeenCalledWith('房型最多 100 个字符')

    await roomTypeInput(wrapper).setValue('双床房')
    await noteInput(wrapper).setValue('说'.repeat(1001))
    await buttonByText(wrapper, '保存行程').trigger('click')
    await flushPromises()
    expect(createItineraryDay).not.toHaveBeenCalled()
    expect(ElMessage.warning).toHaveBeenCalledWith('住宿说明最多 1000 个字符')

    await noteInput(wrapper).setValue('以出团通知为准')
    await buttonByText(wrapper, '保存行程').trigger('click')
    await flushPromises()
    expect(createItineraryDay).toHaveBeenCalledTimes(1)
  })
})

it('编辑待确认住宿只修改标题时，保留住宿标准、房型及明确的不含早餐', async () => {
  fetchRoute.mockResolvedValue(mockRouteDetail([{ ...standardDay, accommodationType: 'PENDING' }]))
  const wrapper = mountView()
  await flushPromises()
  await editDayButton(wrapper).trigger('click')
  await flushPromises()
  expect(roomTypeInput(wrapper).element.value).toBe('大床房')
  expect(breakfastSelect(wrapper).element.value).toBe('false')
  await dayTitleInput(wrapper).setValue('修改标题')
  await buttonByText(wrapper, '保存行程').trigger('click')
  await flushPromises()
  expect(updateItineraryDay.mock.calls[0][1]).toMatchObject({ accommodationType: 'PENDING',
    hotelId: null, accommodationStandard: '市区舒适型酒店', roomType: '大床房', breakfastIncluded: false })
})

it('酒店改为待确认时保留住宿信息并清除酒店关联', async () => {
  const wrapper = mountView()
  await flushPromises()
  await editDayButton(wrapper).trigger('click')
  await flushPromises()
  await accommodationSelect(wrapper).setValue('PENDING')
  await flushPromises()
  await buttonByText(wrapper, '保存行程').trigger('click')
  await flushPromises()
  expect(updateItineraryDay.mock.calls[0][1]).toMatchObject({ accommodationType: 'PENDING', hotelId: null,
    roomType: '双床房', breakfastIncluded: true })
})

/**
 * 线路侧的 {@code validBookingCount} 是「有效报名订单条数」（不是游客人数）——
 * 契约里同名的人数字段是 {@code popularDestinations[].validBookingCount}。
 * 基本资料这里此前写作「有效报名人次 … 人」，与工作台的「有效报名 X 单」和字段真实含义都不一致。
 */
it('有效报名订单数按「单」展示，不再标成「人次 / 人」', async () => {
  fetchRoute.mockResolvedValue({
    ...mockRouteDetail(),
    route: { id: '21', name: '云南 6 日', status: 'DRAFT', durationDays: 6, validBookingCount: 132 }
  })
  const wrapper = mountView()
  await flushPromises()

  const grid = wrapper.find('.info-grid')
  expect(grid.text()).toContain('有效报名订单数')
  expect(grid.text()).toContain('132 单')
  expect(grid.text()).not.toContain('人次')
})
