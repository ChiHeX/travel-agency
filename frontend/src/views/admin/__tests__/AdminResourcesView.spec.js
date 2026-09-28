// @vitest-environment jsdom
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import AdminResourcesView from '../AdminResourcesView.vue'

/**
 * 后台景点资料库页面的接线测试。
 *
 * <p>此前该页面只有一张只读表，"新增景点资料"按钮只弹一句
 * 「新增表单已对接对应后端 CRUD API」的提示，既没有表单也没有编辑/删除入口 ——
 * 后端契约里的 POST/PUT/DELETE /admin/attractions 在前端没有任何调用方。
 * 这里钉住的是补上之后的接线：列表按 keyword 查询、新增/编辑打开弹窗、
 * 删除走契约端点并在取消时不发请求。</p>
 */

const fetchAttractions = vi.fn()
const deleteAttraction = vi.fn()
const confirmMock = vi.fn()

vi.mock('@/api/modules', () => ({
  adminApi: {
    attractions: (...args) => fetchAttractions(...args),
    deleteAttraction: (...args) => deleteAttraction(...args),
    hotels: vi.fn(),
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

function mountView() {
  return mount(AdminResourcesView, {
    props: { title: '景点管理', resource: 'attractions' },
    global: {
      // el-skeleton 由应用全局注册；两个弹窗在这里只验证开关，不验证内部表单。
      stubs: { 'el-skeleton': true, AttractionFormDialog: true, DepartureFormDialog: true }
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
