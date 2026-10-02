// @vitest-environment jsdom
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import AdminPlaceGuidesView from '../AdminPlaceGuidesView.vue'

/**
 * 后台「地点指南管理」页的接线测试。
 *
 * <p>此前契约里的 {@code /admin/place-guides} 系列端点只有后端实现，前端没有任何入口 ——
 * 指南只能靠直接改数据库维护。这里钉住补上之后的接线：
 * 列表按 page/size 拉取、编辑前先取详情（列表的 places 为空数组，不取详情会把地点清空）、
 * 发布 / 下线走 PATCH 状态端点并保持本地状态与库内一致。</p>
 */

const listPlaceGuides = vi.fn()
const fetchPlaceGuide = vi.fn()
const updatePlaceGuideStatus = vi.fn()
const confirmMock = vi.fn()

vi.mock('@/api/modules', () => ({
  adminApi: {
    placeGuides: (...args) => listPlaceGuides(...args),
    placeGuide: (...args) => fetchPlaceGuide(...args),
    updatePlaceGuideStatus: (...args) => updatePlaceGuideStatus(...args),
    createPlaceGuide: vi.fn(),
    updatePlaceGuide: vi.fn()
  }
}))

vi.mock('element-plus', () => ({
  ElMessage: { success: vi.fn(), warning: vi.fn(), error: vi.fn(), info: vi.fn() },
  ElMessageBox: { confirm: (...args) => confirmMock(...args) }
}))

const publishedGuide = {
  id: '7',
  title: '杭州双景点地图演示指南',
  summary: '课程测试指南',
  city: '杭州',
  destination: '杭州',
  coverUrl: 'https://example.com/cover.jpg',
  status: 'PUBLISHED',
  authorId: '3',
  authorName: 'guide_editor',
  publishedAt: '2026-10-01T10:00:00+08:00',
  places: []
}

const draftGuide = {
  ...publishedGuide,
  id: '8',
  title: '北京中轴线地图演示指南',
  city: '北京',
  destination: '北京',
  status: 'DRAFT',
  publishedAt: null
}

function mountView() {
  return mount(AdminPlaceGuidesView, {
    global: {
      stubs: { 'el-skeleton': true, RouterLink: true, PlaceGuideFormDialog: true }
    }
  })
}

function buttonByText(scope, text) {
  const button = scope.findAll('button').find((item) => item.text().includes(text))
  if (!button) {
    throw new Error(`找不到按钮「${text}」，现有按钮：${scope.findAll('button').map((b) => b.text()).join(' / ')}`)
  }
  return button
}

beforeEach(() => {
  // 每次返回全新对象：页面在发布成功后会就地改写行状态（Object.assign），
  // 复用同一份 fixture 会让上一个用例的改动泄漏到下一个用例。
  listPlaceGuides.mockReset().mockResolvedValue({
    items: [{ ...publishedGuide }, { ...draftGuide }],
    total: 2,
    totalPages: 1
  })
  fetchPlaceGuide.mockReset().mockResolvedValue({ ...draftGuide, places: [{ attractionId: '12', note: '地点一' }, { attractionId: '13', note: '地点二' }] })
  updatePlaceGuideStatus.mockReset()
  confirmMock.mockReset().mockResolvedValue('confirm')
})

describe('AdminPlaceGuidesView', () => {
  it('挂载时按 page/size 拉取列表并渲染状态与操作', async () => {
    const wrapper = mountView()
    await flushPromises()

    expect(listPlaceGuides).toHaveBeenCalledWith({ page: 1, size: 20 })
    const text = wrapper.text()
    expect(text).toContain('杭州双景点地图演示指南')
    expect(text).toContain('已发布')
    expect(text).toContain('草稿')
    // 已发布的显示「下线」，草稿显示「发布」。
    expect(wrapper.findAll('button').some((b) => b.text().includes('下线'))).toBe(true)
    expect(wrapper.findAll('button').some((b) => b.text().includes('发布'))).toBe(true)
  })

  it('编辑前先按主键取详情，再打开弹窗', async () => {
    const wrapper = mountView()
    await flushPromises()

    const rows = wrapper.findAll('tbody tr')
    await buttonByText(rows[1], '编辑').trigger('click')
    await flushPromises()

    expect(fetchPlaceGuide).toHaveBeenCalledWith('8')
  })

  it('草稿「发布」经确认后走 PATCH 状态端点', async () => {
    updatePlaceGuideStatus.mockResolvedValue({ ...draftGuide, status: 'PUBLISHED' })
    const wrapper = mountView()
    await flushPromises()

    const rows = wrapper.findAll('tbody tr')
    await buttonByText(rows[1], '发布').trigger('click')
    await flushPromises()

    expect(confirmMock).toHaveBeenCalledTimes(1)
    expect(updatePlaceGuideStatus).toHaveBeenCalledWith('8', 'PUBLISHED')
  })

  it('取消确认时不发状态请求', async () => {
    confirmMock.mockReset().mockRejectedValue(new Error('cancel'))
    const wrapper = mountView()
    await flushPromises()

    const rows = wrapper.findAll('tbody tr')
    await buttonByText(rows[1], '发布').trigger('click')
    await flushPromises()

    expect(updatePlaceGuideStatus).not.toHaveBeenCalled()
  })

  it('分页控件按 totalPages 切换页码', async () => {
    listPlaceGuides.mockResolvedValue({ items: [publishedGuide], total: 40, totalPages: 2 })
    const wrapper = mountView()
    await flushPromises()

    await buttonByText(wrapper, '下一页').trigger('click')
    await flushPromises()

    expect(listPlaceGuides).toHaveBeenLastCalledWith({ page: 2, size: 20 })
  })
})
