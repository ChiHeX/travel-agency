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

vi.mock('@/api/modules', () => ({
  adminApi: {
    route: (...args) => fetchRoute(...args),
    hotels: (...args) => fetchHotels(...args),
    attractions: (...args) => fetchAttractions(...args),
    createItineraryDay: (...args) => createItineraryDay(...args)
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
  items: []
}

function mockRouteDetail() {
  return {
    route: { id: '21', name: '云南 6 日', status: 'DRAFT', durationDays: 6 },
    departures: [],
    itinerary: [dayOnDisabledHotel],
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

function hotelOptions(wrapper) {
  return wrapper.findAll('option').filter((option) => option.text().includes('酒店'))
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
