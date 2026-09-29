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
const confirmMock = vi.fn()

vi.mock('@/api/modules', () => ({
  adminApi: {
    attractions: (...args) => fetchAttractions(...args),
    deleteAttraction: (...args) => deleteAttraction(...args),
    hotels: (...args) => fetchHotels(...args),
    deleteHotel: (...args) => deleteHotel(...args),
    guides: vi.fn(),
    departures: vi.fn(),
    refunds: vi.fn()
  }
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

/** 契约 Hotel 的字段形状：没有 city，状态是 AccountStatus，坐标是 JSON number。 */
const activeHotel = {
  id: '31',
  name: '杭州湖畔演示酒店',
  address: '浙江省杭州市西湖区',
  contactPhone: '000-00000001',
  longitude: 120.139,
  latitude: 30.229,
  intro: '课程演示用酒店资料',
  dataSource: '团队原创测试资料',
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

function buttonByText(wrapper, text) {
  const button = wrapper.findAll('button').find((item) => item.text().includes(text))
  if (!button) {
    throw new Error(`找不到按钮「${text}」，现有按钮：${wrapper.findAll('button').map((b) => b.text()).join(' / ')}`)
  }
  return button
}

beforeEach(() => {
  fetchAttractions.mockReset().mockResolvedValue({ items: [activeAttraction], total: 1, totalPages: 1 })
  deleteAttraction.mockReset().mockResolvedValue(undefined)
  fetchHotels.mockReset().mockResolvedValue({ items: [activeHotel], total: 1, totalPages: 1 })
  deleteHotel.mockReset().mockResolvedValue(undefined)
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
  it('按契约分页参数拉取酒店列表，并显示联系方式、坐标与枚举状态', async () => {
    const wrapper = mountHotelView()
    await flushPromises()

    expect(fetchHotels).toHaveBeenCalledTimes(1)
    expect(fetchHotels).toHaveBeenCalledWith({ page: 1, size: 20 })
    expect(wrapper.text()).toContain('杭州湖畔演示酒店')
    expect(wrapper.text()).toContain('000-00000001')
    expect(wrapper.text()).toContain('团队原创测试资料')
    // 枚举状态要显示成中文，而不是把库内的 1/ACTIVE 直接抛给运营
    expect(wrapper.text()).toContain('启用')
    // 酒店资料没有 city 字段，不能渲染成"所属城市"列
    expect(wrapper.text()).not.toContain('所属城市')
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
})
