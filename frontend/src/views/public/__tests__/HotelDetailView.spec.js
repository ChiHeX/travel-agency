// @vitest-environment jsdom
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import HotelDetailView from '../HotelDetailView.vue'

/**
 * 用户端酒店详情页（契约 {@code GET /routes/{routeId}/hotels/{hotelId}} → {@code PublicHotelDetail}）。
 *
 * <p>这里要钉住的是"展示契约真实返回的字段"这件事本身：</p>
 * <ul>
 *   <li>请求必须打在新的公开端点上，并且**只**用路由参数；</li>
 *   <li>封面回退顺序是 {@code coverUrl → images[0] → 占位块}，{@code images} 为 {@code []} 时不许报错；</li>
 *   <li>官方星级只在非空时出现；设施里的未知枚举跳过而不是把枚举原文印出来；</li>
 *   <li>坐标只有成对且为数字时才传给主地图（契约明确不许前端自行编造位置）；</li>
 *   <li>接口失败（未发布线路 / 未安排该酒店 / 酒店已停用都返回同一个 404）时给用户可读文案，
 *       而不是把后端错误码抛出来，并且保留顶部返回入口。</li>
 * </ul>
 *
 * <p>地图组件（leaflet）与粘性标题栏在测试里替换成轻量替身，只断言"要不要渲染它们"。</p>
 */
const { api } = vi.hoisted(() => ({ api: { hotel: vi.fn() } }))
vi.mock('@/api/modules', () => ({ routeApi: api }))
vi.mock('vue-router', () => ({ useRoute: () => ({ params: { routeId: '7', hotelId: '31' } }) }))
vi.mock('element-plus', () => ({
  ElMessage: { success: vi.fn(), warning: vi.fn(), error: vi.fn(), info: vi.fn() }
}))

function publicHotel(overrides = {}) {
  return {
    id: '31',
    name: '杭州湖畔演示酒店',
    city: '杭州',
    address: '浙江省杭州市西湖区湖畔路 1 号',
    coverUrl: 'https://example.com/hotel-31-cover.jpg',
    images: [
      { url: 'https://example.com/hotel-31-1.jpg', alt: '酒店外观', sortOrder: 1 },
      { url: 'https://example.com/hotel-31-2.jpg', alt: null, sortOrder: 2 }
    ],
    starRating: 4,
    intro: '紧邻湖畔的演示酒店资料。',
    facilities: ['WIFI', 'PARKING', 'BREAKFAST_SERVICE'],
    checkInTime: '14:00',
    checkOutTime: '12:00',
    longitude: 120.139,
    latitude: 30.229,
    dataSource: '团队原创测试资料',
    ...overrides
  }
}

const setMapFocus = vi.fn()

function mountView() {
  return mount(HotelDetailView, {
    global: {
      provide: { setMapFocus },
      stubs: {
        StickyDetailBar: {
          props: ['title', 'fallbackTo'],
          emits: ['share'],
          template: '<div class="bar" :data-name="fallbackTo.name" :data-id="fallbackTo.params.id"><span class="bar-title">{{ title }}</span><button class="bar-share" @click="$emit(\'share\')">分享</button></div>'
        },
        'el-skeleton': true
      }
    }
  })
}

beforeEach(() => {
  setMapFocus.mockClear()
  api.hotel.mockReset().mockResolvedValue(publicHotel())
})

describe('HotelDetailView 数据映射', () => {
  it('调用公开酒店端点（线路 + 酒店两个路由参数），并映射名称/城市/地址/星级/时间/资料来源', async () => {
    const wrapper = mountView()
    await flushPromises()

    expect(api.hotel).toHaveBeenCalledTimes(1)
    expect(api.hotel).toHaveBeenCalledWith('7', '31')

    const text = wrapper.text()
    expect(wrapper.find('.bar-title').text()).toBe('杭州湖畔演示酒店')
    expect(text).toContain('杭州湖畔演示酒店')
    expect(text).toContain('杭州')
    expect(text).toContain('浙江省杭州市西湖区湖畔路 1 号')
    expect(text).toContain('星级')
    expect(text).toContain('4 星')
    expect(text).toContain('14:00 起')
    expect(text).toContain('12:00 前')
    expect(text).toContain('紧邻湖畔的演示酒店资料。')
    expect(text).toContain('团队原创测试资料')
    expect(text).toContain('Wi-Fi')
    expect(text).toContain('停车场')
    expect(text).toContain('早餐服务')
    expect(text).not.toContain('评分')
    expect(text).not.toContain('null')
    expect(text).not.toContain('undefined')
  })

  it('图片列表按服务端顺序渲染，并带上 alt（为空时回退成酒店名）', async () => {
    const wrapper = mountView()
    await flushPromises()

    const images = wrapper.findAll('.hotel-gallery img')
    expect(images).toHaveLength(2)
    expect(images.map((image) => image.attributes('src'))).toEqual([
      'https://example.com/hotel-31-1.jpg',
      'https://example.com/hotel-31-2.jpg'
    ])
    expect(images[0].attributes('alt')).toBe('酒店外观')
    expect(images[1].attributes('alt')).toBe('杭州湖畔演示酒店')
  })

  it('未提供的字段（简介 / 地址 / 星级 / 时间）不渲染空行，也不打印 null', async () => {
    api.hotel.mockResolvedValue(publicHotel({
      address: null, starRating: null, intro: null, checkInTime: null, checkOutTime: null, dataSource: null
    }))
    const wrapper = mountView()
    await flushPromises()

    const text = wrapper.text()
    expect(text).not.toContain('星级')
    expect(text).not.toContain('入住')
    expect(text).not.toContain('退房')
    expect(text).not.toContain('资料来源')
    expect(text).toContain('酒店暂未提供公开简介。')
    expect(text).not.toContain('null')
    expect(text).not.toContain('undefined')
  })

  it('设施是未知枚举时整项跳过，不把枚举原文抛给用户', async () => {
    api.hotel.mockResolvedValue(publicHotel({ facilities: ['WIFI', 'SAUNA'] }))
    const wrapper = mountView()
    await flushPromises()

    expect(wrapper.text()).toContain('Wi-Fi')
    expect(wrapper.text()).not.toContain('SAUNA')
    expect(wrapper.findAll('.hotel-facility')).toHaveLength(1)
  })

  it('设施为空数组时给出空状态，而不是空卡片', async () => {
    api.hotel.mockResolvedValue(publicHotel({ facilities: [] }))
    const wrapper = mountView()
    await flushPromises()

    expect(wrapper.text()).toContain('酒店暂未提供设施资料。')
    expect(wrapper.findAll('.hotel-facility')).toHaveLength(0)
  })
})

describe('HotelDetailView 封面回退', () => {
  it('coverUrl 为空时回退到 images[0].url', async () => {
    api.hotel.mockResolvedValue(publicHotel({ coverUrl: null }))
    const wrapper = mountView()
    await flushPromises()

    const cover = wrapper.find('.hotel-cover-media')
    expect(cover.exists()).toBe(true)
    expect(cover.attributes('src')).toBe('https://example.com/hotel-31-1.jpg')
    expect(cover.attributes('alt')).toBe('杭州湖畔演示酒店')
    expect(wrapper.find('.hotel-cover').exists()).toBe(false)
  })

  it('coverUrl 与 images 都为空时显示占位块（images 是 [] 不是错误）', async () => {
    api.hotel.mockResolvedValue(publicHotel({ coverUrl: null, images: [] }))
    const wrapper = mountView()
    await flushPromises()

    expect(wrapper.find('.hotel-cover-media').exists()).toBe(false)
    expect(wrapper.find('.hotel-cover').exists()).toBe(true)
    expect(wrapper.text()).toContain('酒店图片暂未提供')
    expect(wrapper.find('.hotel-gallery').exists()).toBe(false)
  })
})

describe('HotelDetailView 地图与返回入口', () => {
  it('将酒店坐标和名称传给大地图，不再渲染小地图，并在离开时清理标记', async () => {
    const wrapper = mountView()
    await flushPromises()
    expect(setMapFocus).toHaveBeenLastCalledWith({
      name: '杭州湖畔演示酒店', longitude: 120.139, latitude: 30.229
    })
    expect(wrapper.find('.hotel-map').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('酒店位置')
    wrapper.unmount()
    expect(setMapFocus).toHaveBeenLastCalledWith(null)
  })

  it.each([
    { longitude: null, latitude: null },
    { longitude: 120.139, latitude: null },
    { longitude: 181, latitude: 30.229 }
  ])('坐标缺失或越界时不向大地图传入酒店位置：%o', async (coordinates) => {
    api.hotel.mockResolvedValue(publicHotel(coordinates))
    const wrapper = mountView()
    await flushPromises()
    expect(setMapFocus).toHaveBeenCalledTimes(1)
    expect(setMapFocus).toHaveBeenLastCalledWith(null)
    expect(wrapper.find('.hotel-map').exists()).toBe(false)
  })

  it('顶部返回入口指向当前线路，正文不再展示重复返回按钮', async () => {
    const wrapper = mountView()
    await flushPromises()

    expect(wrapper.find('.bar').attributes('data-name')).toBe('route-detail')
    expect(wrapper.find('.bar').attributes('data-id')).toBe('7')
    expect(wrapper.find('.hotel-back').exists()).toBe(false)
  })
})

describe('HotelDetailView 请求失败', () => {

  it('404 时给出可读文案而不是原始错误，并保留返回线路详情的出口', async () => {
    api.hotel.mockRejectedValue(Object.assign(new Error('资源不存在'), {
      status: 404,
      code: 'RESOURCE_NOT_FOUND'
    }))
    const wrapper = mountView()
    await flushPromises()

    const text = wrapper.text()
    expect(text).toContain('该酒店资料暂不可查看')
    expect(text).not.toContain('资源不存在')
    expect(text).not.toContain('RESOURCE_NOT_FOUND')
    expect(wrapper.find('.bar-title').text()).toBe('酒店详情')
    expect(api.hotel).toHaveBeenCalledTimes(1)
    expect(wrapper.find('.bar').attributes('data-name')).toBe('route-detail')
    expect(wrapper.find('.bar').attributes('data-id')).toBe('7')
    expect(wrapper.find('.hotel-back').exists()).toBe(false)
    expect(wrapper.find('.map-stub').exists()).toBe(false)
  })

  it('点「重新加载」会用同样的参数重试', async () => {
    api.hotel.mockRejectedValueOnce(Object.assign(new Error('网络异常'), { status: 500 }))
    const wrapper = mountView()
    await flushPromises()

    expect(wrapper.text()).toContain('该酒店资料暂不可查看')

    api.hotel.mockResolvedValue(publicHotel())
    const retry = wrapper.findAll('button').find((button) => button.text().includes('重新加载'))
    expect(retry).toBeTruthy()
    await retry.trigger('click')
    await flushPromises()

    expect(api.hotel).toHaveBeenCalledTimes(2)
    expect(api.hotel).toHaveBeenLastCalledWith('7', '31')
    expect(wrapper.text()).toContain('杭州湖畔演示酒店')
  })
})
