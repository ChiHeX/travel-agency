// @vitest-environment jsdom
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import AdminDashboardView from '../AdminDashboardView.vue'

/**
 * 后台工作台（Dashboard 数据统计）的接线测试。
 *
 * <p>此前页面只渲染 8 个标量指标：契约里 required 的 {@code orderTrend} / {@code popularRoutes} /
 * {@code popularDestinations} 完全没有被消费，页面上留着「后端提供数据后，此处显示」的占位文案，
 * {@code days}（7/30）窗口也没有任何入口，请求失败还会被显示成「暂无统计数据」。
 * 这里钉住补齐之后的接线：窗口参数真的发给后端、三个数组真的渲染、失败可重试、
 * 过期响应不会覆盖新窗口的数据。</p>
 */

const dashboard = vi.fn()

vi.mock('@/api/modules', () => ({
  adminApi: { dashboard: (...args) => dashboard(...args) }
}))

const RouterLinkStub = {
  name: 'RouterLink',
  props: { to: { type: [String, Object], required: true } },
  template: '<a><slot /></a>'
}

const metric = (date, orderCount, participantCount, orderAmount) => ({ date, orderCount, participantCount, orderAmount })

function dateOffset(index, total) {
  const base = Date.UTC(2026, 9, 1)
  return new Date(base + (index - (total - 1)) * 86400000).toISOString().slice(0, 10)
}

/** 近 7 天：0/2/0/5/1/0/3 —— 含补零日期，用来验证空白天也出现在坐标轴上。 */
const sevenDayTrend = [0, 2, 0, 5, 1, 0, 3].map((orders, index) =>
  metric(dateOffset(index, 7), orders, orders * 2, `${orders * 100}.00`))

const thirtyDayTrend = Array.from({ length: 30 }, (_, index) =>
  metric(dateOffset(index, 30), index === 29 ? 4 : 0, index === 29 ? 8 : 0, index === 29 ? '400.00' : '0.00'))

function dashboardData(overrides = {}) {
  return {
    userCount: 128,
    publishedRouteCount: 12,
    openDepartureCount: 8,
    todayOrderCount: 6,
    pendingConfirmCount: 3,
    pendingRefundCount: 1,
    participantCount: 256,
    grossOrderAmount: '123456.70',
    orderTrend: sevenDayTrend,
    popularRoutes: [
      {
        id: '3',
        name: '昆明大理丽江 6 日跟团游',
        departureCity: '上海',
        destination: '云南',
        durationDays: 6,
        ratingAvg: '4.80',
        ratingCount: 26,
        validBookingCount: 132,
        status: 'PUBLISHED'
      }
    ],
    popularDestinations: [
      { destination: '云南', validBookingCount: 6 },
      { destination: '北京', validBookingCount: 3 },
      { destination: '成都', validBookingCount: 0 }
    ],
    departureEnrollment: [
      {
        departureId: '11',
        routeId: '3',
        routeName: '昆明大理丽江 6 日跟团游',
        startDate: '2026-10-15',
        maxPeople: 30,
        reservedPeople: 2,
        confirmedPeople: 18,
        remainingSeats: 10
      },
      {
        departureId: '12',
        routeId: '4',
        routeName: '北京中轴线文化 4 日跟团游',
        startDate: '2026-10-22',
        maxPeople: 25,
        reservedPeople: 0,
        confirmedPeople: 25,
        remainingSeats: 0
      }
    ],
    ...overrides
  }
}

function mountView() {
  return mount(AdminDashboardView, {
    global: {
      stubs: { 'el-skeleton': true, RouterLink: RouterLinkStub }
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
  dashboard.mockReset()
})

describe('AdminDashboardView', () => {
  it('挂载时按契约默认窗口 days=7 拉取，并渲染标量指标与金额', async () => {
    dashboard.mockResolvedValue(dashboardData())
    const wrapper = mountView()
    await flushPromises()

    expect(dashboard).toHaveBeenCalledWith({ days: 7 })
    const text = wrapper.text()
    expect(text).toContain('128')
    expect(text).toContain('已支付订单总额')
    expect(text).toContain('¥123456.70')
    expect(wrapper.findAll('.stat-card')).toHaveLength(8)
  })

  it('渲染 orderTrend：补零日期也出现在坐标轴上，合计由返回序列累加得到', async () => {
    dashboard.mockResolvedValue(dashboardData())
    const wrapper = mountView()
    await flushPromises()

    // 7 天里只有 4 天有订单，柱子 4 根，但 7 个日期刻度都要在（后端补零后的完整序列）。
    expect(wrapper.findAll('rect.chart-bar')).toHaveLength(4)
    expect(wrapper.findAll('text.chart-label')).toHaveLength(7)
    expect(wrapper.find('.chart-legend').text()).toContain('订单 11 单')
    expect(wrapper.find('.chart-legend').text()).toContain('报名 22 人次')
    expect(wrapper.find('.chart-legend').text()).toContain('已支付 ¥1100.00')
  })

  it('切换「近 30 天」会用 days=30 重新拉取并渲染该窗口', async () => {
    dashboard.mockResolvedValue(dashboardData())
    const wrapper = mountView()
    await flushPromises()

    dashboard.mockResolvedValue(dashboardData({ orderTrend: thirtyDayTrend }))
    await buttonByText(wrapper, '近 30 天').trigger('click')
    await flushPromises()

    expect(dashboard).toHaveBeenLastCalledWith({ days: 30 })
    expect(wrapper.findAll('rect.chart-bar')).toHaveLength(1)
    expect(wrapper.find('.chart-legend').text()).toContain('订单 4 单')
    expect(buttonByText(wrapper, '近 30 天').attributes('aria-pressed')).toBe('true')
  })

  it('热门目的地按报名人次展示，条形宽度按最大值归一、0 不画', async () => {
    dashboard.mockResolvedValue(dashboardData())
    const wrapper = mountView()
    await flushPromises()

    const rows = wrapper.findAll('.ranking-row')
    expect(rows).toHaveLength(3)
    expect(rows[0].text()).toContain('云南')
    expect(rows[0].text()).toContain('6 人次')
    expect(rows[0].text()).not.toContain('6 单')
    const widths = wrapper.findAll('.rank-bar').map((bar) => bar.attributes('style'))
    expect(widths[0]).toContain('width: 100%')
    expect(widths[1]).toContain('width: 50%')
    expect(widths[2]).toContain('width: 0%')
  })

  it('渲染 popularRoutes 并链接到后台线路详情', async () => {
    dashboard.mockResolvedValue(dashboardData())
    const wrapper = mountView()
    await flushPromises()

    // 团期报名情况的卡片也是 RouterLink，这里只看热门线路那一组。
    const links = wrapper.findAllComponents(RouterLinkStub)
      .filter((link) => link.classes().includes('popular-route-row'))
    expect(links).toHaveLength(1)
    expect(links[0].props('to')).toEqual({ name: 'admin-route-detail', params: { id: '3' } })
    expect(links[0].text()).toContain('昆明大理丽江 6 日跟团游')
    expect(links[0].text()).toContain('有效报名 132 单')
  })

  it('渲染 departureEnrollment：名额占用、剩余位与已满标签都来自接口数据', async () => {
    dashboard.mockResolvedValue(dashboardData())
    const wrapper = mountView()
    await flushPromises()

    const cards = wrapper.findAll('.enrollment-card')
    expect(cards).toHaveLength(2)
    expect(cards[0].text()).toContain('昆明大理丽江 6 日跟团游')
    expect(cards[0].text()).toContain('出发 2026-10-15')
    expect(cards[0].text()).toContain('已确认 18 / 30 人')
    expect(cards[0].text()).toContain('待确认 2 人')
    expect(cards[0].text()).toContain('剩余 10 位')
    // 进度条是「已确认 + 待确认」占名额的比例：(18 + 2) / 30 = 67%
    expect(cards[0].find('.enrollment-bar').attributes('style')).toContain('width: 67%')

    expect(cards[1].text()).toContain('名额已满')
    expect(cards[1].find('.enrollment-bar').attributes('style')).toContain('width: 100%')

    const links = wrapper.findAllComponents(RouterLinkStub)
      .filter((link) => link.classes().includes('enrollment-route'))
    expect(links.map((link) => link.props('to'))).toEqual([
      { name: 'admin-route-detail', params: { id: '3' } },
      { name: 'admin-route-detail', params: { id: '4' } }
    ])
  })

  it('已占用超过名额时进度条封顶 100%，剩余为 0 显示已满，不出现负数', async () => {
    dashboard.mockResolvedValue(dashboardData({
      departureEnrollment: [{
        departureId: '13',
        routeId: '3',
        routeName: '脏数据团期',
        startDate: '2026-11-01',
        maxPeople: 8,
        reservedPeople: 9,
        confirmedPeople: 9,
        remainingSeats: 0
      }]
    }))
    const wrapper = mountView()
    await flushPromises()

    const card = wrapper.find('.enrollment-card')
    expect(card.find('.enrollment-bar').attributes('style')).toContain('width: 100%')
    expect(card.text()).toContain('名额已满')
    expect(card.text()).not.toContain('剩余 -')
  })

  it('没有未出发的开放团期时显示空状态', async () => {
    dashboard.mockResolvedValue(dashboardData({ departureEnrollment: [] }))
    const wrapper = mountView()
    await flushPromises()

    expect(wrapper.findAll('.enrollment-card')).toHaveLength(0)
    expect(wrapper.text()).toContain('暂无未出发的开放团期。')
  })

  it('请求失败显示错误与重试入口，而不是「暂无统计数据」', async () => {
    dashboard.mockRejectedValueOnce(new Error('统计数据加载失败'))
    const wrapper = mountView()
    await flushPromises()

    expect(wrapper.find('.dashboard-error').exists()).toBe(true)
    expect(wrapper.find('.dashboard-error').text()).toContain('统计数据加载失败')

    dashboard.mockResolvedValue(dashboardData())
    await buttonByText(wrapper.find('.dashboard-error'), '重新加载').trigger('click')
    await flushPromises()

    expect(wrapper.find('.dashboard-error').exists()).toBe(false)
    expect(wrapper.findAll('.stat-card')).toHaveLength(8)
  })

  it('切换窗口加载中或失败时不展示旧窗口的图表，重试成功才展示新数据', async () => {
    dashboard.mockResolvedValueOnce(dashboardData())
    const wrapper = mountView()
    await flushPromises()

    let rejectThirty
    dashboard.mockImplementationOnce(() => new Promise((resolve, reject) => { rejectThirty = reject }))
    await buttonByText(wrapper, '近 30 天').trigger('click')
    await flushPromises()

    expect(wrapper.find('.trend-svg').exists()).toBe(false)
    expect(wrapper.find('.chart-legend').exists()).toBe(false)
    expect(wrapper.find('.trend-state').text()).toContain('正在加载近 30 天')

    rejectThirty(new Error('统计服务暂不可用'))
    await flushPromises()
    expect(wrapper.find('.dashboard-error').text()).toContain('统计服务暂不可用')
    expect(wrapper.find('.trend-svg').exists()).toBe(false)
    expect(wrapper.find('.chart-legend').exists()).toBe(false)
    expect(wrapper.find('.trend-state').text()).toContain('近 30 天订单趋势加载失败')

    dashboard.mockResolvedValueOnce(dashboardData({ orderTrend: thirtyDayTrend }))
    await buttonByText(wrapper.find('.dashboard-error'), '重新加载').trigger('click')
    await flushPromises()
    expect(dashboard).toHaveBeenLastCalledWith({ days: 30 })
    expect(wrapper.find('.trend-svg').attributes('aria-label')).toContain('近 30 天')
    expect(wrapper.find('.chart-legend').text()).toContain('订单 4 单')
    expect(wrapper.find('.dashboard-error').exists()).toBe(false)
  })

  it('后端补零的无订单窗口保留日期刻度和零合计，并说明没有订单记录', async () => {
    const emptyTrend = sevenDayTrend.map((item) => metric(item.date, 0, 0, '0.00'))
    dashboard.mockResolvedValueOnce(dashboardData({ orderTrend: emptyTrend }))
    const wrapper = mountView()
    await flushPromises()

    expect(wrapper.findAll('rect.chart-bar')).toHaveLength(0)
    expect(wrapper.findAll('text.chart-label')).toHaveLength(7)
    expect(wrapper.find('.chart-legend').text()).toContain('订单 0 单')
    expect(wrapper.text()).toContain('该窗口内没有订单记录。')
  })

  it('过期响应被丢弃：先发的窗口请求后返回，不覆盖当前窗口的数据', async () => {
    dashboard.mockResolvedValueOnce(dashboardData())
    const wrapper = mountView()
    await flushPromises()
    expect(wrapper.find('.chart-legend').text()).toContain('订单 11 单')

    // 两次切换都还没有结果：7 天 → 30 天 → 7 天，最后发出的请求才是当前窗口。
    let resolveThirty
    let resolveSeven
    dashboard.mockImplementationOnce(() => new Promise((resolve) => { resolveThirty = resolve }))
    await buttonByText(wrapper, '近 30 天').trigger('click')
    await flushPromises()

    dashboard.mockImplementationOnce(() => new Promise((resolve) => { resolveSeven = resolve }))
    await buttonByText(wrapper, '近 7 天').trigger('click')
    await flushPromises()

    expect(dashboard).toHaveBeenNthCalledWith(2, { days: 30 })
    expect(dashboard).toHaveBeenNthCalledWith(3, { days: 7 })

    resolveSeven(dashboardData())
    await flushPromises()
    expect(wrapper.find('.chart-legend').text()).toContain('订单 11 单')

    // 30 天窗口的响应姗姗来迟，必须被丢弃：否则图会退回上一次窗口的数据。
    resolveThirty(dashboardData({ orderTrend: thirtyDayTrend }))
    await flushPromises()

    expect(wrapper.find('.chart-legend').text()).toContain('订单 11 单')
    expect(wrapper.find('.chart-legend').text()).not.toContain('订单 4 单')
    expect(wrapper.findAll('rect.chart-bar')).toHaveLength(4)
    expect(buttonByText(wrapper, '近 7 天').attributes('aria-pressed')).toBe('true')
  })
})
