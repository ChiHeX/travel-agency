// @vitest-environment jsdom
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { ElMessage } from 'element-plus'
import AdminRouteDetailView from '../AdminRouteDetailView.vue'

/**
 * 线路详情页「每日行程」表单的接线测试，针对酒店相关的两条规则：
 *
 * <ol>
 *   <li>酒店 / 景点候选项必须逐页取全量：只取第一页时，第 101 条之后的资源
 *       在界面上永远选不到（既看不到也没法安排进行程）；</li>
 *   <li>停用（DISABLED）的酒店不能再被安排进新行程（后端以 422 拒绝），
 *       下拉里要禁掉并标注；但"这一天原本就指向它"时必须保持可选 ——
 *       否则编辑这条行程时下拉显示为空，连带改个餐食说明都会保存失败。</li>
 * </ol>
 *
 * <p>另外钉住行程文本的长度口径：契约 ItineraryDayRequest / ItineraryItemRequest 的
 * maxLength 数的是<b>字符（Unicode 码点）</b>，后端也以 @CodePointLength 按同一口径校验，
 * 而 JS 的 String#length 数的是 UTF-16 码元（一个 emoji 记 2）。用码元判断会把契约允许的
 * emoji 文案误判成超长；此前输入框上的 maxlength 更糟——它会直接静默截断用户输入。</p>
 */
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

/** 契约 Hotel：停用的那家仍要出现在下拉里（否则看不到它被停用了）。 */
const activeHotel = { id: '31', name: '杭州湖畔演示酒店', status: 'ACTIVE' }
const disabledHotel = { id: '32', name: '已停用演示酒店', status: 'DISABLED' }

/** 这一天原本就安排在已停用的酒店上：编辑它时该酒店必须保持可选。 */
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
        // 弹窗 stub 直接渲染内容，便于断言下拉选项；其余组件与本用例无关。
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

/** 每天的「编辑」按钮：取第 index 张行程卡片上的那个。 */
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
  const select = wrapper.findAll('select')
    .find((item) => item.findAll('option').some((option) => option.text() === '未说明'))
  if (!select) throw new Error('找不到早餐下拉')
  return select
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

/** 每日行程弹窗里的「行程标题」输入框：占位符在全页唯一。 */
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
    // 第 2 页的停用酒店同样在选项里（不再被静默截断）
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
    // 另一家启用酒店同样保持可选，运营可以主动换走。
    expect(options.find((option) => option.text().includes('杭州湖畔')).attributes('disabled')).toBeUndefined()
  })
})

/**
 * 行程标题的长度口径（契约 ItineraryDayRequest.title：minLength 1 / maxLength 200）。
 *
 * <p>maxLength 数的是字符（码点），一个 emoji 记 1；JS 的 String#length 与 HTML 的 maxlength
 * 数的是 UTF-16 码元，同一个 emoji 记 2。用码元判断时，200 个 emoji 的标题（码元长度 400）
 * 会被前端当成超长而拒绝，而它其实是契约允许、库内也存得下的输入。</p>
 */
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
    // 提交的标题一字不少：既没有被判成超长，也没有被静默截断
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
