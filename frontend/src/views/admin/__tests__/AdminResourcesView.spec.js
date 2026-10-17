// @vitest-environment jsdom
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import AdminResourcesView from '../AdminResourcesView.vue'

/**
 * 后台景点 / 酒店资料库页面的接线测试。
 *
 * <p>此前该页面只有一张只读表，"新增景点资料"按钮只弹一句
 * 「新增表单已对接对应后端 CRUD API」的提示，既没有表单也没有编辑/删除入口 ——
 * 后端契约里的 POST/PUT/DELETE /admin/attractions 在前端没有任何调用方。
 * 这里钉住的是补上之后的接线：列表按 keyword 查询、新增/编辑打开弹窗、
 * 删除走契约端点并在取消时不发请求。</p>
 *
 * <p>酒店资料（契约 /admin/hotels）随后按同一方式接入：它同样有 page/size/keyword
 * 与 201/404/204/409 的写入端点，因此这里覆盖的是"酒店也真的接上了"，
 * 而不是把它留在只读的通用表格分支里。</p>
 */

const fetchAttractions = vi.fn()
const deleteAttraction = vi.fn()
const fetchHotels = vi.fn()
const deleteHotel = vi.fn()
const fetchGuides = vi.fn()
const updateGuideStatus = vi.fn()
const confirmMock = vi.fn()

vi.mock('@/api/modules', () => ({
  adminApi: {
    attractions: (...args) => fetchAttractions(...args),
    deleteAttraction: (...args) => deleteAttraction(...args),
    hotels: (...args) => fetchHotels(...args),
    deleteHotel: (...args) => deleteHotel(...args),
    guides: (...args) => fetchGuides(...args),
    updateGuideStatus: (...args) => updateGuideStatus(...args),
    departures: vi.fn(),
    refunds: vi.fn()
  }
}))

/**
 * 角色用可变状态驱动：契约里 POST /admin/guides 与 PATCH /admin/guides/{id}/status 只对 ADMIN 开放，
 * 页面据此决定是否渲染「新增导游」「停用」按钮，因此测试要能分别以 ADMIN / STAFF 身份挂载。
 */
const { authState } = vi.hoisted(() => ({ authState: { roles: ['ADMIN'] } }))
vi.mock('@/stores/auth', () => ({
  useAuthStore: () => ({ hasRole: (role) => authState.roles.includes(role) })
}))

vi.mock('element-plus', () => ({
  ElMessage: { success: vi.fn(), warning: vi.fn(), error: vi.fn(), info: vi.fn() },
  ElMessageBox: { confirm: (...args) => confirmMock(...args) }
}))

const activeAttraction = {
  id: '12',
  name: '西湖',
  city: '杭州',
  address: '浙江省杭州市西湖区',
  longitude: 120.13,
  latitude: 30.24,
  intro: '演示简介',
  dataSource: '团队测试数据',
  status: 'ACTIVE',
  createdAt: '2026-09-01T10:00:00+08:00',
  updatedAt: '2026-09-01T10:00:00+08:00'
}

const activeHotel = {
  id: '31',
  name: '杭州湖畔演示酒店',
  city: '杭州',
  address: '浙江省杭州市西湖区',
  contactPhone: '000-00000001',
  coverUrl: null,
  starRating: 4,
  facilities: ['WIFI'],
  checkInTime: '14:00',
  checkOutTime: '12:00',
  images: [],
  longitude: 120.139,
  latitude: 30.229,
  intro: '课程演示用酒店资料',
  dataSource: '团队原创测试资料',
  status: 'ACTIVE',
  createdAt: '2026-09-01T10:00:00+08:00',
  updatedAt: '2026-09-01T10:00:00+08:00'
}

/** 契约 Guide 的字段形状：含 username 与 AccountStatus 状态。 */
const activeGuide = {
  id: '7',
  userId: '3',
  username: 'guide_lee',
  name: '李导',
  phone: '13800138001',
  intro: '具有云南线路带团经验。',
  status: 'ACTIVE',
  createdAt: '2026-09-01T10:00:00+08:00',
  updatedAt: '2026-09-01T10:00:00+08:00'
}

function mountView() {
  return mount(AdminResourcesView, {
    props: { title: '景点管理', resource: 'attractions' },
    global: {
      // el-skeleton 由应用全局注册；两个弹窗在这里只验证开关，不验证内部表单。
      stubs: { 'el-skeleton': true, AttractionFormDialog: true, DepartureFormDialog: true, HotelFormDialog: true }
    }
  })
}

function mountHotelView() {
  return mount(AdminResourcesView, {
    props: { title: '酒店资料', resource: 'hotels' },
    global: {
      stubs: { 'el-skeleton': true, AttractionFormDialog: true, DepartureFormDialog: true, HotelFormDialog: true }
    }
  })
}

function mountGuideView() {
  return mount(AdminResourcesView, {
    props: { title: '导游管理', resource: 'guides' },
    global: {
      stubs: { 'el-skeleton': true, GuideFormDialog: true }
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

beforeEach(() => {
  authState.roles = ['ADMIN']
  fetchAttractions.mockReset().mockResolvedValue({ items: [activeAttraction], total: 1, totalPages: 1 })
  deleteAttraction.mockReset().mockResolvedValue(undefined)
  fetchHotels.mockReset().mockResolvedValue({ items: [activeHotel], total: 1, totalPages: 1 })
  deleteHotel.mockReset().mockResolvedValue(undefined)
  // 每次调用都返回一份拷贝：组件会就地改写行对象（状态切换 / 回滚），
  // 共用同一个 fixture 会让上一个用例的改动泄漏到下一个用例。
  fetchGuides.mockReset().mockImplementation(() =>
    Promise.resolve({ items: [{ ...activeGuide }], total: 1, totalPages: 1 })
  )
  updateGuideStatus.mockReset()
  confirmMock.mockReset().mockResolvedValue('confirm')
})

describe('AdminResourcesView（景点资料库）', () => {
  it('进入页面即按契约分页参数拉取景点列表，并显示启用状态与资料来源', async () => {
    const wrapper = mountView()
    await flushPromises()

    expect(fetchAttractions).toHaveBeenCalledTimes(1)
    expect(fetchAttractions).toHaveBeenCalledWith({ page: 1, size: 20 })
    expect(wrapper.text()).toContain('西湖')
    expect(wrapper.text()).toContain('团队测试数据')
    expect(wrapper.text()).toContain('启用')
  })

  it('提交搜索时把 keyword 交给契约参数并回到第 1 页，而不是前端自行过滤', async () => {
    const wrapper = mountView()
    await flushPromises()

    await wrapper.find('.resource-search input').setValue('西湖')
    await wrapper.find('.resource-search').trigger('submit')
    await flushPromises()

    expect(fetchAttractions).toHaveBeenLastCalledWith({ page: 1, size: 20, keyword: '西湖' })
  })

  it('分页：翻页时按新的 page 重新请求，首/末页按钮分别禁用', async () => {
    fetchAttractions.mockResolvedValue({ items: [activeAttraction], total: 45, totalPages: 3 })
    const wrapper = mountView()
    await flushPromises()

    // 第 1 页：上一页不可点
    expect(buttonByText(wrapper, '上一页').attributes('disabled')).toBeDefined()
    expect(wrapper.text()).toContain('第 1 / 3 页，共 45 条')

    await buttonByText(wrapper, '下一页').trigger('click')
    await flushPromises()
    expect(fetchAttractions).toHaveBeenLastCalledWith({ page: 2, size: 20 })
    expect(wrapper.text()).toContain('第 2 / 3 页，共 45 条')

    await buttonByText(wrapper, '下一页').trigger('click')
    await flushPromises()
    expect(fetchAttractions).toHaveBeenLastCalledWith({ page: 3, size: 20 })
    // 末页：下一页不可点
    expect(buttonByText(wrapper, '下一页').attributes('disabled')).toBeDefined()
  })

  it('删除末页最后一条后回退一页，不停在空页上', async () => {
    // 第 1 次：第 2 页有 1 条；删除后第 2 页取空 → 应回退到第 1 页重取。
    fetchAttractions
      .mockResolvedValueOnce({ items: [activeAttraction], total: 21, totalPages: 2 })
      .mockResolvedValueOnce({ items: [activeAttraction], total: 21, totalPages: 2 })
      .mockResolvedValueOnce({ items: [], total: 20, totalPages: 1 })
      .mockResolvedValueOnce({ items: [activeAttraction], total: 20, totalPages: 1 })
    const wrapper = mountView()
    await flushPromises()

    await buttonByText(wrapper, '下一页').trigger('click')
    await flushPromises()
    expect(fetchAttractions).toHaveBeenLastCalledWith({ page: 2, size: 20 })

    await buttonByText(wrapper, '删除').trigger('click')
    await flushPromises()

    expect(fetchAttractions).toHaveBeenLastCalledWith({ page: 1, size: 20 })
    expect(wrapper.text()).toContain('第 1 / 1 页，共 20 条')
  })

  it('新增与编辑分别以空行/当前行打开表单弹窗', async () => {
    const wrapper = mountView()
    await flushPromises()

    const dialog = () => wrapper.findComponent({ name: 'AttractionFormDialog' })
    expect(dialog().props('modelValue')).toBe(false)

    await buttonByText(wrapper, '新增景点资料').trigger('click')
    await flushPromises()
    expect(dialog().props('modelValue')).toBe(true)
    expect(dialog().props('attraction')).toBeNull()

    await buttonByText(wrapper, '编辑').trigger('click')
    await flushPromises()
    expect(dialog().props('attraction')).toMatchObject({ id: '12', name: '西湖' })
  })

  /**
   * 保存成功后的刷新页码必须按 POST / PUT 分开。
   *
   * <p>改动前的实现两种情况都回第 1 页：在第 2 页改完一个景点后列表跳回第 1 页，
   * 被改的那条（created_at 没变，仍在第 2 页）从视野里消失，看起来像被删掉了。</p>
   */
  it('编辑保存后留在当前页（连同筛选条件），新增保存后才回到第 1 页', async () => {
    fetchAttractions.mockResolvedValue({ items: [activeAttraction], total: 45, totalPages: 3 })
    const wrapper = mountView()
    await flushPromises()

    // 翻到第 2 页并带上筛选词
    await wrapper.find('.resource-search input').setValue('西湖')
    await wrapper.find('.resource-search').trigger('submit')
    await flushPromises()
    await buttonByText(wrapper, '下一页').trigger('click')
    await flushPromises()
    expect(fetchAttractions).toHaveBeenLastCalledWith({ page: 2, size: 20, keyword: '西湖' })

    const dialog = wrapper.findComponent({ name: 'AttractionFormDialog' })

    // 修改（PUT）：created_at 不变，记录仍在第 2 页，不能跳回第 1 页
    dialog.vm.$emit('saved', { attraction: activeAttraction, created: false })
    await flushPromises()
    expect(fetchAttractions).toHaveBeenLastCalledWith({ page: 2, size: 20, keyword: '西湖' })
    expect(wrapper.text()).toContain('第 2 / 3 页')

    // 新增（POST）：记录排在第一页，必须先回到第 1 页才看得到
    dialog.vm.$emit('saved', { attraction: activeAttraction, created: true })
    await flushPromises()
    expect(fetchAttractions).toHaveBeenLastCalledWith({ page: 1, size: 20, keyword: '西湖' })
    expect(wrapper.text()).toContain('第 1 / 3 页')
  })

  it('删除：确认后调用契约端点并刷新列表', async () => {
    const wrapper = mountView()
    await flushPromises()

    await buttonByText(wrapper, '删除').trigger('click')
    await flushPromises()

    expect(confirmMock).toHaveBeenCalledTimes(1)
    expect(deleteAttraction).toHaveBeenCalledWith('12')
    expect(fetchAttractions).toHaveBeenCalledTimes(2)
  })

  it('删除：用户在确认框里取消时不发请求', async () => {
    confirmMock.mockRejectedValue(new Error('cancel'))
    const wrapper = mountView()
    await flushPromises()

    await buttonByText(wrapper, '删除').trigger('click')
    await flushPromises()

    expect(deleteAttraction).not.toHaveBeenCalled()
    expect(fetchAttractions).toHaveBeenCalledTimes(1)
  })
})

/**
 * 酒店资料库（契约 {@code Admin Resources} 的 {@code /admin/hotels}）。
 *
 * <p>改动前酒店走的是通用只读表格分支：表头按"所属城市/地址"渲染（契约 Hotel 没有 city 字段）、
 * 状态直接显示库内的 1、没有新增表单、没有编辑与删除入口，后端契约里的
 * POST/PUT/DELETE /admin/hotels 在前端没有任何调用方。这里钉住接入后的行为。</p>
 */
describe('AdminResourcesView（酒店资料库）', () => {
  it('按契约分页参数拉取酒店列表，并显示城市、联系方式、坐标与枚举状态', async () => {
    const wrapper = mountHotelView()
    await flushPromises()

    expect(fetchHotels).toHaveBeenCalledTimes(1)
    expect(fetchHotels).toHaveBeenCalledWith({ page: 1, size: 20 })
    expect(wrapper.text()).toContain('杭州湖畔演示酒店')
    expect(wrapper.text()).toContain('000-00000001')
    expect(wrapper.text()).toContain('团队原创测试资料')
    // 枚举状态要显示成中文，而不是把库内的 1/ACTIVE 直接抛给运营
    expect(wrapper.text()).toContain('启用')
    expect(wrapper.text()).toContain('杭州')
    expect(wrapper.text()).not.toContain('所属城市')
  })

  it('城市为空串（迁移脚本补的存量数据）时显示占位符，而不是空白单元格或编造的城市', async () => {
    fetchHotels.mockResolvedValue({
      items: [{ ...activeHotel, city: '' }],
      total: 1,
      totalPages: 1
    })
    const wrapper = mountHotelView()
    await flushPromises()

    const cells = wrapper.findAll('tbody tr td')
    expect(cells[1].text()).toBe('—')
  })

  it('城市筛选按精确匹配提交契约参数，并回到第 1 页', async () => {
    const wrapper = mountHotelView()
    await flushPromises()

    expect(wrapper.find('.city-filter').exists()).toBe(true)
    await wrapper.find('.resource-search input').setValue('湖畔')
    await wrapper.find('.city-filter').setValue('杭州')
    await wrapper.find('.resource-search').trigger('submit')
    await flushPromises()

    expect(fetchHotels).toHaveBeenLastCalledWith({ page: 1, size: 20, keyword: '湖畔', city: '杭州' })
  })

  it('城市筛选可与翻页同时使用，清空按钮同时清掉关键字与城市', async () => {
    fetchHotels.mockResolvedValue({ items: [activeHotel], total: 45, totalPages: 3 })
    const wrapper = mountHotelView()
    await flushPromises()

    await wrapper.find('.city-filter').setValue('杭州')
    await wrapper.find('.resource-search').trigger('submit')
    await flushPromises()
    await buttonByText(wrapper, '下一页').trigger('click')
    await flushPromises()
    expect(fetchHotels).toHaveBeenLastCalledWith({ page: 2, size: 20, city: '杭州' })

    await buttonByText(wrapper, '清空').trigger('click')
    await flushPromises()
    expect(fetchHotels).toHaveBeenLastCalledWith({ page: 1, size: 20 })
    expect(wrapper.find('.city-filter').element.value).toBe('')
  })

  it('城市筛选无命中时给出针对"城市"的提示', async () => {
    fetchHotels.mockResolvedValue({ items: [], total: 0, totalPages: 0 })
    const wrapper = mountHotelView()
    await flushPromises()

    await wrapper.find('.city-filter').setValue('不存在的城市')
    await wrapper.find('.resource-search').trigger('submit')
    await flushPromises()

    expect(wrapper.text()).toContain('没有位于「不存在的城市」的酒店资料')
  })

  it('景点列表不渲染城市筛选，也从不发送契约没有的 city 参数', async () => {
    const wrapper = mountView()
    await flushPromises()

    expect(wrapper.find('.city-filter').exists()).toBe(false)
    await wrapper.find('.resource-search input').setValue('西湖')
    await wrapper.find('.resource-search').trigger('submit')
    await flushPromises()

    expect(fetchAttractions).toHaveBeenLastCalledWith({ page: 1, size: 20, keyword: '西湖' })
    expect(fetchAttractions.mock.calls.every((call) => !call[0].city)).toBe(true)
  })

  it('提交搜索时把 keyword 交给契约参数并回到第 1 页，而不是前端自行过滤', async () => {
    const wrapper = mountHotelView()
    await flushPromises()

    await wrapper.find('.resource-search input').setValue('湖畔')
    await wrapper.find('.resource-search').trigger('submit')
    await flushPromises()

    expect(fetchHotels).toHaveBeenLastCalledWith({ page: 1, size: 20, keyword: '湖畔' })
  })

  it('分页：翻页时按新的 page 重新请求，首/末页按钮分别禁用', async () => {
    fetchHotels.mockResolvedValue({ items: [activeHotel], total: 45, totalPages: 3 })
    const wrapper = mountHotelView()
    await flushPromises()

    expect(buttonByText(wrapper, '上一页').attributes('disabled')).toBeDefined()
    expect(wrapper.text()).toContain('第 1 / 3 页，共 45 条')

    await buttonByText(wrapper, '下一页').trigger('click')
    await flushPromises()
    expect(fetchHotels).toHaveBeenLastCalledWith({ page: 2, size: 20 })
    expect(wrapper.text()).toContain('第 2 / 3 页，共 45 条')
  })

  it('新增与编辑分别以空行/当前行打开表单弹窗，保存后按 POST/PUT 决定刷新哪一页', async () => {
    fetchHotels.mockResolvedValue({ items: [activeHotel], total: 45, totalPages: 3 })
    const wrapper = mountHotelView()
    await flushPromises()

    const dialog = () => wrapper.findComponent({ name: 'HotelFormDialog' })
    expect(dialog().props('modelValue')).toBe(false)

    await buttonByText(wrapper, '新增酒店资料').trigger('click')
    await flushPromises()
    expect(dialog().props('modelValue')).toBe(true)
    expect(dialog().props('hotel')).toBeNull()

    await buttonByText(wrapper, '编辑').trigger('click')
    await flushPromises()
    expect(dialog().props('hotel')).toMatchObject({ id: '31', name: '杭州湖畔演示酒店' })

    // 修改（PUT）：记录位置不变，留在当前页
    await buttonByText(wrapper, '下一页').trigger('click')
    await flushPromises()
    expect(fetchHotels).toHaveBeenLastCalledWith({ page: 2, size: 20 })
    dialog().vm.$emit('saved', { hotel: activeHotel, created: false })
    await flushPromises()
    expect(fetchHotels).toHaveBeenLastCalledWith({ page: 2, size: 20 })

    // 新增（POST）：新记录排在第一页
    dialog().vm.$emit('saved', { hotel: activeHotel, created: true })
    await flushPromises()
    expect(fetchHotels).toHaveBeenLastCalledWith({ page: 1, size: 20 })
  })

  it('删除：确认后调用契约端点并刷新列表，取消时不发请求', async () => {
    const wrapper = mountHotelView()
    await flushPromises()

    await buttonByText(wrapper, '删除').trigger('click')
    await flushPromises()

    expect(confirmMock).toHaveBeenCalledTimes(1)
    expect(deleteHotel).toHaveBeenCalledWith('31')
    expect(deleteAttraction).not.toHaveBeenCalled()
    expect(fetchHotels).toHaveBeenCalledTimes(2)

    confirmMock.mockRejectedValue(new Error('cancel'))
    await buttonByText(wrapper, '删除').trigger('click')
    await flushPromises()
    expect(deleteHotel).toHaveBeenCalledTimes(1)
  })

  /**
   * 删除失败（例如被线路行程引用时后端回 409）也必须重新拉列表。
   *
   * <p>失败提示由 axios 拦截器统一弹出，页面自己不再提示；但若失败路径不刷新，
   * 页面会停在一个与库内不符的状态上——工作人员会对着一条"看起来还在"的记录反复点击。
   * 因此刷新放在 finally 里。</p>
   */
  it('删除失败（409 引用冲突）时仍然刷新列表，页面不停留在过期状态', async () => {
    deleteHotel.mockRejectedValue(
      Object.assign(new Error('该酒店已被线路行程引用，不能删除'), { status: 409, code: 'HOTEL_STATE_CONFLICT' })
    )
    const wrapper = mountHotelView()
    await flushPromises()

    await buttonByText(wrapper, '删除').trigger('click')
    await flushPromises()

    expect(deleteHotel).toHaveBeenCalledWith('31')
    expect(fetchHotels).toHaveBeenCalledTimes(2)
    expect(wrapper.text()).toContain('杭州湖畔演示酒店')
  })

  it('停用的酒店显示「停用」标签，而不是把库内的枚举直接抛给运营', async () => {
    fetchHotels.mockResolvedValue({
      items: [{ ...activeHotel, status: 'DISABLED' }],
      total: 1,
      totalPages: 1
    })
    const wrapper = mountHotelView()
    await flushPromises()

    expect(wrapper.text()).toContain('停用')
    expect(wrapper.text()).not.toContain('DISABLED')
  })

  it('筛选无命中时给出针对酒店的提示，而不是笼统的空状态', async () => {
    fetchHotels.mockResolvedValue({ items: [], total: 0, totalPages: 0 })
    const wrapper = mountHotelView()
    await flushPromises()

    expect(wrapper.text()).toContain('暂无相关资料数据')

    await wrapper.find('.resource-search input').setValue('不存在的酒店')
    await wrapper.find('.resource-search').trigger('submit')
    await flushPromises()

    expect(wrapper.text()).toContain('没有匹配「不存在的酒店」的酒店资料')
  })
})

/**
 * 景点与酒店共用同一个页面组件（{@code AdminResourcesView} 通过 {@code resource} 切换）。
 * 切换时必须清掉上一个资源的筛选词与页码，否则会把景点的 keyword/页码带到酒店列表上，
 * 直接落到一个空的酒店页。
 */
describe('AdminResourcesView（资源切换）', () => {
  it('从景点切到酒店：清空筛选词与页码，改用酒店接口并带上酒店自己的分页参数', async () => {
    const wrapper = mountView()
    await flushPromises()

    await wrapper.find('.resource-search input').setValue('西湖')
    await wrapper.find('.resource-search').trigger('submit')
    await flushPromises()
    expect(fetchAttractions).toHaveBeenLastCalledWith({ page: 1, size: 20, keyword: '西湖' })

    await wrapper.setProps({ resource: 'hotels' })
    await flushPromises()

    expect(fetchHotels).toHaveBeenLastCalledWith({ page: 1, size: 20 })
    expect(wrapper.find('.resource-search input').element.value).toBe('')
    expect(wrapper.findComponent({ name: 'HotelFormDialog' }).exists()).toBe(true)
    expect(wrapper.findComponent({ name: 'AttractionFormDialog' }).exists()).toBe(false)
  })
})

/**
 * 后台导游管理（契约 {@code Admin Resources} 的 {@code /admin/guides}）。
 *
 * <p>改动前该资源走的是只读的通用表格分支：「新增」按钮只弹一句
 * 「新增表单已对接对应后端 CRUD API」的占位提示，行内没有编辑/启停入口，
 * 状态列恒为绿色 {@code success} 标签（把库内枚举直接抛给运营），
 * 契约里的 POST/PUT/PATCH 在前端没有任何调用方。这里钉住接入后的行为。</p>
 */
describe('AdminResourcesView（导游管理）', () => {
  it('按契约分页参数拉取导游列表，显示账号与中文枚举状态，且不再有任何占位提示按钮', async () => {
    const wrapper = mountGuideView()
    await flushPromises()

    expect(fetchGuides).toHaveBeenCalledTimes(1)
    expect(fetchGuides).toHaveBeenCalledWith({ page: 1, size: 20 })
    expect(wrapper.text()).toContain('李导')
    expect(wrapper.text()).toContain('guide_lee')
    expect(wrapper.text()).toContain('13800138001')
    // 状态要显示成中文，而不是把库内的 ACTIVE/DISABLED 直接抛给运营
    expect(wrapper.text()).toContain('启用')
    expect(wrapper.text()).not.toContain('ACTIVE')
    // 占位按钮必须消失：ADMIN 看到的是真正可用的「+ 新增导游」
    expect(wrapper.text()).not.toContain('新增表单已对接对应后端 CRUD API')
    expect(buttonByText(wrapper, '新增导游').exists()).toBe(true)
  })

  it('停用的导游显示「停用」标签，并给停用行渲染「启用」操作', async () => {
    fetchGuides.mockResolvedValue({
      items: [{ ...activeGuide, status: 'DISABLED' }],
      total: 1,
      totalPages: 1
    })
    const wrapper = mountGuideView()
    await flushPromises()

    expect(wrapper.text()).toContain('停用')
    expect(wrapper.text()).not.toContain('DISABLED')
    expect(buttonByText(wrapper, '启用').exists()).toBe(true)
  })

  it('导游列表有 page/size 分页，且不发送契约未声明的 keyword 参数', async () => {
    fetchGuides.mockResolvedValue({ items: [activeGuide], total: 45, totalPages: 3 })
    const wrapper = mountGuideView()
    await flushPromises()

    // 第 21 条之后必须还能在界面上管理
    expect(wrapper.text()).toContain('第 1 / 3 页，共 45 条')
    // 契约 GET /admin/guides 没有 keyword 参数，因此不渲染搜索框
    expect(wrapper.find('.resource-search').exists()).toBe(false)

    await buttonByText(wrapper, '下一页').trigger('click')
    await flushPromises()
    expect(fetchGuides).toHaveBeenLastCalledWith({ page: 2, size: 20 })
  })

  it('新增与编辑分别以空行/当前行打开表单弹窗，保存后按 POST/PUT 决定刷新哪一页', async () => {
    fetchGuides.mockResolvedValue({ items: [activeGuide], total: 45, totalPages: 3 })
    const wrapper = mountGuideView()
    await flushPromises()

    const dialog = () => wrapper.findComponent({ name: 'GuideFormDialog' })
    expect(dialog().props('modelValue')).toBe(false)

    await buttonByText(wrapper, '新增导游').trigger('click')
    await flushPromises()
    expect(dialog().props('modelValue')).toBe(true)
    expect(dialog().props('guide')).toBeNull()

    await buttonByText(wrapper, '编辑').trigger('click')
    await flushPromises()
    expect(dialog().props('guide')).toMatchObject({ id: '7', name: '李导' })

    // 修改（PUT）：记录位置不变，留在当前页
    await buttonByText(wrapper, '下一页').trigger('click')
    await flushPromises()
    expect(fetchGuides).toHaveBeenLastCalledWith({ page: 2, size: 20 })
    dialog().vm.$emit('saved', { guide: activeGuide, created: false })
    await flushPromises()
    expect(fetchGuides).toHaveBeenLastCalledWith({ page: 2, size: 20 })

    // 新增（POST）：新记录排在第一页
    dialog().vm.$emit('saved', { guide: activeGuide, created: true })
    await flushPromises()
    expect(fetchGuides).toHaveBeenLastCalledWith({ page: 1, size: 20 })
  })

  it('停用导游：确认后调用契约端点并更新行状态', async () => {
    updateGuideStatus.mockResolvedValue({ ...activeGuide, status: 'DISABLED' })
    const wrapper = mountGuideView()
    await flushPromises()

    await buttonByText(wrapper, '停用').trigger('click')
    await flushPromises()

    expect(updateGuideStatus).toHaveBeenCalledWith('7', 'DISABLED')
    expect(wrapper.text()).toContain('停用')
  })

  it('停用失败时把本地状态回滚，页面不停留在未落库的状态上', async () => {
    updateGuideStatus.mockRejectedValue(
      Object.assign(new Error('导游尚未关联有效账号'), { status: 409, code: 'GUIDE_ACCOUNT_CONFLICT' })
    )
    const wrapper = mountGuideView()
    await flushPromises()

    await buttonByText(wrapper, '停用').trigger('click')
    await flushPromises()

    expect(updateGuideStatus).toHaveBeenCalledWith('7', 'DISABLED')
    // 回滚成 ACTIVE：界面显示"启用"，与库内一致
    expect(wrapper.text()).toContain('启用')
    expect(buttonByText(wrapper, '停用').exists()).toBe(true)
  })

  /**
   * 同一导游的启停必须串行。
   *
   * <p>状态是「乐观改写 + 失败回滚」，按钮文案又由 `row.status` 反推。若允许第二个请求
   * 在第一个还没回来时就发出，两个请求都失败时按后进先出回滚：后发的把状态写回
   * `DISABLED`，而库内其实仍是 `ACTIVE`（第一次请求从未落库），页面停在与数据库相反的状态上。</p>
   */
  it('启停在途时按钮禁用，连点不会发出第二个请求，失败后回滚到库内真实状态', async () => {
    let rejectFirst
    updateGuideStatus
      .mockImplementationOnce(() => new Promise((_resolve, reject) => { rejectFirst = reject }))
      .mockRejectedValue(Object.assign(new Error('conflict'), { status: 409, code: 'GUIDE_ACCOUNT_CONFLICT' }))

    const wrapper = mountGuideView()
    await flushPromises()

    // 第一次点击：请求在途，按钮进入禁用态并显示"提交中…"
    await buttonByText(wrapper, '停用').trigger('click')

    const toggling = buttonByText(wrapper, '提交中')
    expect(toggling.attributes('disabled')).toBeDefined()

    // 在途期间连点：无论是隔着禁用态点原按钮，还是直接再触发一次点击，都不得发出第二个请求
    await toggling.trigger('click')
    expect(updateGuideStatus).toHaveBeenCalledTimes(1)

    rejectFirst(Object.assign(new Error('conflict'), { status: 409, code: 'GUIDE_ACCOUNT_CONFLICT' }))
    await flushPromises()

    // 回滚到 ACTIVE —— 与库内一致，而不是停在"停用"
    expect(wrapper.text()).toContain('启用')
    expect(buttonByText(wrapper, '停用').exists()).toBe(true)
    expect(buttonByText(wrapper, '停用').attributes('disabled')).toBeUndefined()
  })

  it('两个导游可以各自独立启停，互不阻塞', async () => {
    fetchGuides.mockResolvedValue({
      items: [{ ...activeGuide }, { ...activeGuide, id: '8', username: 'guide_wang', name: '王导' }],
      total: 2,
      totalPages: 1
    })
    updateGuideStatus.mockResolvedValue({ ...activeGuide, status: 'DISABLED' })
    const wrapper = mountGuideView()
    await flushPromises()

    const stops = wrapper.findAll('button').filter((item) => item.text() === '停用')
    expect(stops).toHaveLength(2)
    await stops[0].trigger('click')
    await stops[1].trigger('click')
    await flushPromises()

    expect(updateGuideStatus).toHaveBeenCalledTimes(2)
    expect(updateGuideStatus.mock.calls.map((call) => call[0])).toEqual(['7', '8'])
  })

  /**

  /**
   * 契约把「创建导游账号」与「启用/停用」都限制为 ADMIN，而「修改资料」对 STAFF 开放。
   * 页面必须按同一口径渲染，否则 STAFF 会点进一个必然 403 的操作。
   */
  it('STAFF：不显示「新增导游」与「停用」，但保留「编辑」', async () => {
    authState.roles = ['STAFF']
    const wrapper = mountGuideView()
    await flushPromises()

    expect(fetchGuides).toHaveBeenCalledTimes(1)
    expect(wrapper.findAll('button').some((item) => item.text().includes('新增导游'))).toBe(false)
    expect(wrapper.findAll('button').some((item) => item.text().includes('停用'))).toBe(false)
    expect(buttonByText(wrapper, '编辑').exists()).toBe(true)
  })
})
