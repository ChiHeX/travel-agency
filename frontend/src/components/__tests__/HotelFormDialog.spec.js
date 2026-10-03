// @vitest-environment jsdom
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { ElMessage } from 'element-plus'
import HotelFormDialog from '../HotelFormDialog.vue'

const createHotel = vi.fn()
const updateHotel = vi.fn()
const fetchHotel = vi.fn()
const fetchHotels = vi.fn()

vi.mock('@/api/modules', () => ({
  adminApi: {
    createHotel: (...args) => createHotel(...args),
    updateHotel: (...args) => updateHotel(...args),
    hotel: (...args) => fetchHotel(...args),
    hotels: (...args) => fetchHotels(...args)
  }
}))

vi.mock('element-plus', () => ({
  ElMessage: { success: vi.fn(), warning: vi.fn(), error: vi.fn(), info: vi.fn() }
}))

const disabledHotel = {
  id: '31',
  name: '苍山脚下的演示酒店',
  city: '大理',
  address: '云南省大理市',
  contactPhone: '0872-1234567',
  coverUrl: 'https://example.com/hotel-31-cover.jpg',
  starRating: 3,
  facilities: ['WIFI', 'PARKING'],
  checkInTime: '14:00',
  checkOutTime: '12:00',
  images: [
    { url: 'https://example.com/hotel-31-1.jpg', alt: '大堂', sortOrder: 1 },
    { url: 'https://example.com/hotel-31-2.jpg', alt: null, sortOrder: 2 }
  ],
  longitude: 100.1005,
  latitude: 25.6896,
  intro: '演示简介',
  dataSource: '团队测试数据',
  status: 'DISABLED',
  version: 3,
  createdAt: '2026-09-01T10:00:00+08:00',
  updatedAt: '2026-09-02T10:00:00+08:00'
}

const activeHotel = { ...disabledHotel, id: '32', name: '杭州湖畔演示酒店', status: 'ACTIVE' }

function mountDialog(hotel = null) {
  return mount(HotelFormDialog, {
    props: { modelValue: true, hotel },
    global: {
      stubs: {
        'el-dialog': { template: '<div><slot /><slot name="footer" /></div>' },
        'el-skeleton': true
      }
    }
  })
}

function field(wrapper, label) {
  const group = wrapper.findAll('.form-field')
    .find((item) => item.find('label').exists() && item.find('label').text().startsWith(label))
  if (!group) throw new Error(`找不到标签以「${label}」开头的表单项`)
  const control = group.find('input, textarea, select')
  if (!control.exists()) throw new Error(`表单项「${label}」里没有输入控件`)
  return control
}

function buttonByText(wrapper, text) {
  const button = wrapper.findAll('button').find((item) => item.text().includes(text))
  if (!button) {
    throw new Error(`找不到按钮「${text}」，现有按钮：${wrapper.findAll('button').map((b) => b.text()).join(' / ')}`)
  }
  return button
}

function facilityCheckbox(wrapper, value) {
  const input = wrapper.findAll('.facility-option input').find((item) => item.element.value === value)
  if (!input) throw new Error(`找不到设施复选框「${value}」`)
  return input
}

beforeEach(() => {
  createHotel.mockReset()
  updateHotel.mockReset()
  fetchHotel.mockReset().mockRejectedValue(
    Object.assign(new Error('酒店不存在'), { status: 404, code: 'RESOURCE_NOT_FOUND' })
  )
  fetchHotels.mockReset().mockResolvedValue({ items: [], total: 0, totalPages: 0 })
})

describe('HotelFormDialog', () => {
  it('新增：提交契约字段，坐标是 number、可选字段为空时提交 null、状态默认 ACTIVE', async () => {
    createHotel.mockResolvedValue({ id: '1', status: 'ACTIVE' })
    const wrapper = mountDialog()
    await flushPromises()

    await field(wrapper, '酒店名称').setValue(' 大理演示酒店 ')
    await field(wrapper, '城市').setValue(' 大理 ')
    await field(wrapper, '联系电话').setValue('0872-1234567')
    await field(wrapper, '经度').setValue('100.165')
    await field(wrapper, '纬度').setValue('25.694')
    await field(wrapper, '数据来源说明').setValue('团队测试数据')
    await buttonByText(wrapper, '保存酒店').trigger('click')
    await flushPromises()

    expect(createHotel).toHaveBeenCalledTimes(1)
    const payload = createHotel.mock.calls[0][0]
    expect(payload).toEqual({
      name: '大理演示酒店',
      city: '大理',
      address: null,
      contactPhone: '0872-1234567',
      coverUrl: null,
      images: [],
      starRating: null,
      facilities: [],
      checkInTime: null,
      checkOutTime: null,
      longitude: 100.165,
      latitude: 25.694,
      intro: null,
      dataSource: '团队测试数据',
      status: 'ACTIVE'
    })
    expect(payload).not.toHaveProperty('id')
    expect(payload).not.toHaveProperty('createdAt')
    expect(payload).not.toHaveProperty('updatedAt')
    expect(wrapper.emitted('saved')).toHaveLength(1)
    expect(wrapper.emitted('saved')[0][0]).toEqual({ hotel: { id: '1', status: 'ACTIVE' }, created: true })
    expect(wrapper.emitted('update:modelValue').at(-1)).toEqual([false])
  })

  it('编辑已停用的酒店：回填 DISABLED 与新增字段，未改动状态时不提交 status，不会静默改成启用', async () => {
    updateHotel.mockResolvedValue({ ...disabledHotel, name: '苍山脚下的演示酒店（改名）' })
    const wrapper = mountDialog(disabledHotel)
    await flushPromises()

    expect(wrapper.find('select').element.value).toBe('DISABLED')
    expect(field(wrapper, '经度').element.value).toBe('100.1005')
    expect(field(wrapper, '联系电话').element.value).toBe('0872-1234567')
    expect(field(wrapper, '城市').element.value).toBe('大理')
    expect(field(wrapper, '封面图地址').element.value).toBe('https://example.com/hotel-31-cover.jpg')
    expect(field(wrapper, '入住时间').element.value).toBe('14:00')
    expect(field(wrapper, '退房时间').element.value).toBe('12:00')
    expect(field(wrapper, '官方星级').element.value).toBe('3')
    expect(facilityCheckbox(wrapper, 'WIFI').element.checked).toBe(true)
    expect(facilityCheckbox(wrapper, 'GYM').element.checked).toBe(false)
    expect(wrapper.findAll('.image-row')).toHaveLength(2)

    await field(wrapper, '酒店名称').setValue('苍山脚下的演示酒店（改名）')
    await field(wrapper, '城市').setValue('大理')
    await buttonByText(wrapper, '保存酒店').trigger('click')
    await flushPromises()

    expect(updateHotel).toHaveBeenCalledTimes(1)
    expect(updateHotel.mock.calls[0][0]).toBe('31')
    expect(updateHotel.mock.calls[0][1]).toMatchObject({
      name: '苍山脚下的演示酒店（改名）',
      city: '大理',
      contactPhone: '0872-1234567',
      starRating: 3,
      facilities: ['WIFI', 'PARKING'],
      checkInTime: '14:00',
      checkOutTime: '12:00',
      images: [
        { url: 'https://example.com/hotel-31-1.jpg', alt: '大堂', sortOrder: 1 },
        { url: 'https://example.com/hotel-31-2.jpg', alt: null, sortOrder: 2 }
      ],
      longitude: 100.1005,
      latitude: 25.6896
    })
    expect(updateHotel.mock.calls[0][1]).not.toHaveProperty('status')
    expect(updateHotel.mock.calls[0][1].version).toBe(3)
    expect(wrapper.emitted('saved')[0][0]).toEqual({
      hotel: { ...disabledHotel, name: '苍山脚下的演示酒店（改名）' },
      created: false
    })
  })

  it('编辑启用中的酒店：只改地址时不提交 status，改动状态后才提交新值', async () => {
    updateHotel.mockImplementation(async (id, payload) => ({ ...activeHotel, ...payload }))
    const wrapper = mountDialog(activeHotel)
    await flushPromises()

    expect(wrapper.find('select').element.value).toBe('ACTIVE')

    await field(wrapper, '详细地址').setValue('浙江省杭州市西湖区（改）')
    await buttonByText(wrapper, '保存酒店').trigger('click')
    await flushPromises()

    const untouched = updateHotel.mock.calls[0][1]
    expect(untouched).not.toHaveProperty('status')
    expect(untouched.address).toBe('浙江省杭州市西湖区（改）')
    expect(untouched).toHaveProperty('name', '杭州湖畔演示酒店')

    updateHotel.mockClear()
    await wrapper.find('select').setValue('DISABLED')
    await buttonByText(wrapper, '保存酒店').trigger('click')
    await flushPromises()
    expect(updateHotel.mock.calls[0][1].status).toBe('DISABLED')

    updateHotel.mockClear()
    await wrapper.find('select').setValue('ACTIVE')
    await buttonByText(wrapper, '保存酒店').trigger('click')
    await flushPromises()
    expect(updateHotel.mock.calls[0][1].status).toBe('ACTIVE')
  })

  it('新增：无论是否改动状态都提交 status，显式选择 DISABLED 时按停用建档', async () => {
    createHotel.mockResolvedValue({ id: '1', status: 'DISABLED' })
    const wrapper = mountDialog()
    await flushPromises()

    await field(wrapper, '酒店名称').setValue('待停用演示酒店')
    await field(wrapper, '城市').setValue('大理')
    await field(wrapper, '数据来源说明').setValue('团队测试数据')
    await wrapper.find('select').setValue('DISABLED')
    await buttonByText(wrapper, '保存酒店').trigger('click')
    await flushPromises()

    expect(createHotel).toHaveBeenCalledTimes(1)
    expect(createHotel.mock.calls[0][0].status).toBe('DISABLED')
    expect(createHotel.mock.calls[0][0]).not.toHaveProperty('version')
  })

  it('版本冲突：409 时就地展示冲突面板，保留用户输入并列出差异字段', async () => {
    updateHotel.mockRejectedValue(Object.assign(
      new Error('酒店资料已被他人修改（当前版本 5，你提交的是 3），请查看最新数据后再决定是否覆盖'),
      { status: 409, code: 'HOTEL_VERSION_CONFLICT' }
    ))
    const latest = { ...activeHotel, address: '浙江省杭州市西湖区（他人改过）', version: 5 }
    fetchHotel.mockResolvedValue(latest)

    const wrapper = mountDialog(activeHotel)
    await flushPromises()

    await field(wrapper, '详细地址').setValue('我填的地址')
    await buttonByText(wrapper, '保存酒店').trigger('click')
    await flushPromises()

    expect(fetchHotel).toHaveBeenCalledWith('32')
    expect(fetchHotels).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('已被他人修改')
    expect(wrapper.text()).toContain('你填写的内容已保留')
    expect(wrapper.text()).toContain('详细地址')
    expect(wrapper.text()).toContain('服务器当前版本 5')
    expect(wrapper.text()).toContain('浙江省杭州市西湖区（他人改过）')
    expect(field(wrapper, '详细地址').element.value).toBe('我填的地址')
    expect(wrapper.emitted('saved')).toBeUndefined()
    expect(wrapper.emitted('update:modelValue')).toBeUndefined()
  })

  it('版本冲突：对方已改名时，按主键仍能取到最新资料并完成覆盖', async () => {
    updateHotel
      .mockRejectedValueOnce(Object.assign(new Error('酒店资料已被他人修改'), {
        status: 409, code: 'HOTEL_VERSION_CONFLICT'
      }))
      .mockImplementation(async (id, payload) => ({ ...activeHotel, ...payload, version: 6 }))
    const renamed = { ...activeHotel, name: '杭州湖畔演示酒店（改名后的）', address: '新地址', version: 5 }
    fetchHotel.mockResolvedValue(renamed)

    const wrapper = mountDialog(activeHotel)
    await flushPromises()

    await field(wrapper, '详细地址').setValue('我填的地址')
    await buttonByText(wrapper, '保存酒店').trigger('click')
    await flushPromises()

    expect(fetchHotel).toHaveBeenCalledWith('32')
    expect(wrapper.text()).toContain('杭州湖畔演示酒店（改名后的）')
    expect(wrapper.text()).toContain('新地址')
    expect(wrapper.text()).not.toContain('暂时取不到服务器最新资料')

    await buttonByText(wrapper, '保留我的修改并覆盖').trigger('click')
    await flushPromises()

    const payload = updateHotel.mock.calls.at(-1)[1]
    expect(payload.version).toBe(5)
    expect(payload.address).toBe('我填的地址')
    expect(wrapper.emitted('saved')).toHaveLength(1)
  })

  it('版本冲突：载入服务器最新数据后，保存使用服务器最新版本号', async () => {
    updateHotel
      .mockRejectedValueOnce(Object.assign(new Error('酒店资料已被他人修改'), {
        status: 409, code: 'HOTEL_VERSION_CONFLICT'
      }))
      .mockImplementation(async (id, payload) => ({ ...activeHotel, ...payload }))
    const latest = { ...activeHotel, address: '服务器地址', version: 5 }
    fetchHotel.mockResolvedValue(latest)

    const wrapper = mountDialog(activeHotel)
    await flushPromises()

    await field(wrapper, '详细地址').setValue('我填的地址')
    await buttonByText(wrapper, '保存酒店').trigger('click')
    await flushPromises()

    await buttonByText(wrapper, '载入服务器最新数据').trigger('click')
    await flushPromises()

    expect(field(wrapper, '详细地址').element.value).toBe('服务器地址')

    await buttonByText(wrapper, '保存酒店').trigger('click')
    await flushPromises()

    const payload = updateHotel.mock.calls.at(-1)[1]
    expect(payload.version).toBe(5)
    expect(payload.address).toBe('服务器地址')
  })

  it('版本冲突：保留我的修改并覆盖时，用最新版本号重新提交我填写的内容', async () => {
    updateHotel
      .mockRejectedValueOnce(Object.assign(new Error('酒店资料已被他人修改'), {
        status: 409, code: 'HOTEL_VERSION_CONFLICT'
      }))
      .mockImplementation(async (id, payload) => ({ ...activeHotel, ...payload, version: 6 }))
    const latest = { ...activeHotel, address: '服务器地址', version: 5 }
    fetchHotel.mockResolvedValue(latest)

    const wrapper = mountDialog(activeHotel)
    await flushPromises()

    await field(wrapper, '详细地址').setValue('我填的地址')
    await buttonByText(wrapper, '保存酒店').trigger('click')
    await flushPromises()

    await buttonByText(wrapper, '保留我的修改并覆盖').trigger('click')
    await flushPromises()

    expect(updateHotel).toHaveBeenCalledTimes(2)
    const payload = updateHotel.mock.calls.at(-1)[1]
    expect(payload.version).toBe(5)
    expect(payload.address).toBe('我填的地址')
    expect(wrapper.emitted('saved')).toHaveLength(1)
  })

  it('版本冲突：取不到服务器最新资料时给出提示，且不允许覆盖', async () => {
    updateHotel.mockRejectedValue(Object.assign(new Error('酒店资料已被他人修改'), {
      status: 409, code: 'HOTEL_VERSION_CONFLICT'
    }))

    const wrapper = mountDialog(activeHotel)
    await flushPromises()

    await buttonByText(wrapper, '保存酒店').trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('暂时取不到服务器最新资料')
    const overwrite = buttonByText(wrapper, '保留我的修改并覆盖')
    expect(overwrite.attributes('disabled')).toBeDefined()
    expect(buttonByText(wrapper, '载入服务器最新数据').attributes('disabled')).toBeDefined()
  })

  it('版本冲突：资料已被删除时提示重新打开表单', async () => {
    updateHotel.mockRejectedValue(Object.assign(new Error('酒店资料已被他人修改'), {
      status: 409, code: 'HOTEL_VERSION_CONFLICT'
    }))
    fetchHotel.mockRejectedValue(
      Object.assign(new Error('酒店不存在'), { status: 404, code: 'RESOURCE_NOT_FOUND' })
    )

    const wrapper = mountDialog(activeHotel)
    await flushPromises()

    await buttonByText(wrapper, '保存酒店').trigger('click')
    await flushPromises()

    expect(fetchHotel).toHaveBeenCalledWith('32')
    expect(wrapper.text()).toContain('暂时取不到服务器最新资料')
    expect(wrapper.text()).toContain('已被他人删除')
  })

  it('编辑：清空坐标、地址与联系方式时提交 null，PUT 才能真正清空库内字段', async () => {
    updateHotel.mockResolvedValue(disabledHotel)
    const wrapper = mountDialog(disabledHotel)
    await flushPromises()

    await field(wrapper, '经度').setValue('')
    await field(wrapper, '纬度').setValue('')
    await field(wrapper, '详细地址').setValue('')
    await field(wrapper, '联系电话').setValue('   ')
    await buttonByText(wrapper, '保存酒店').trigger('click')
    await flushPromises()

    const payload = updateHotel.mock.calls[0][1]
    expect(payload.longitude).toBeNull()
    expect(payload.latitude).toBeNull()
    expect(payload.address).toBeNull()
    expect(payload.contactPhone).toBeNull()
  })

  it('页面校验：必填缺失、联系电话超长与坐标超范围时不发请求', async () => {
    const wrapper = mountDialog()
    await flushPromises()

    await buttonByText(wrapper, '保存酒店').trigger('click')
    await flushPromises()
    expect(createHotel).not.toHaveBeenCalled()

    await field(wrapper, '酒店名称').setValue('大理演示酒店')
    await field(wrapper, '城市').setValue('大理')
    await field(wrapper, '数据来源说明').setValue('团队测试数据')
    await field(wrapper, '联系电话').setValue('1'.repeat(21))
    await buttonByText(wrapper, '保存酒店').trigger('click')
    await flushPromises()
    expect(createHotel).not.toHaveBeenCalled()

    await field(wrapper, '联系电话').setValue('0872-1234567')
    await field(wrapper, '经度').setValue('181')
    await buttonByText(wrapper, '保存酒店').trigger('click')
    await flushPromises()
    expect(createHotel).not.toHaveBeenCalled()

    await field(wrapper, '经度').setValue('100.165')
    await field(wrapper, '纬度').setValue('91')
    await buttonByText(wrapper, '保存酒店').trigger('click')
    await flushPromises()
    expect(createHotel).not.toHaveBeenCalled()

    await field(wrapper, '纬度').setValue('25.694')
    await buttonByText(wrapper, '保存酒店').trigger('click')
    await flushPromises()
    expect(createHotel).toHaveBeenCalledTimes(1)
  })

  it('经纬度必须成对：只填其中一个时不发请求（契约 CoordinatePairRule）', async () => {
    const wrapper = mountDialog()
    await flushPromises()

    await field(wrapper, '酒店名称').setValue('大理演示酒店')
    await field(wrapper, '城市').setValue('大理')
    await field(wrapper, '数据来源说明').setValue('团队测试数据')

    await field(wrapper, '纬度').setValue('25.694')
    await buttonByText(wrapper, '保存酒店').trigger('click')
    await flushPromises()
    expect(createHotel).not.toHaveBeenCalled()
    expect(ElMessage.warning).toHaveBeenCalledWith('经度和纬度需要同时填写，或同时留空')

    await field(wrapper, '经度').setValue('100.165')
    await buttonByText(wrapper, '保存酒店').trigger('click')
    await flushPromises()
    expect(createHotel).toHaveBeenCalledTimes(1)
  })

  it('校验边界与契约 maxLength 一致：恰好到上限放行，超一个字符拦下', async () => {
    createHotel.mockResolvedValue({ id: '1', status: 'ACTIVE' })
    const wrapper = mountDialog()
    await flushPromises()

    await field(wrapper, '酒店名称').setValue('名'.repeat(128))
    await field(wrapper, '城市').setValue('大理')
    await field(wrapper, '联系电话').setValue('1'.repeat(20))
    await field(wrapper, '数据来源说明').setValue('源'.repeat(500))
    await buttonByText(wrapper, '保存酒店').trigger('click')
    await flushPromises()
    expect(createHotel).toHaveBeenCalledTimes(1)

    await field(wrapper, '酒店名称').setValue('名'.repeat(129))
    await field(wrapper, '城市').setValue('大理')
    await buttonByText(wrapper, '保存酒店').trigger('click')
    await flushPromises()
    expect(createHotel).toHaveBeenCalledTimes(1)

    await field(wrapper, '酒店名称').setValue('名'.repeat(128))
    await field(wrapper, '城市').setValue('大理')
    await field(wrapper, '数据来源说明').setValue('源'.repeat(501))
    await buttonByText(wrapper, '保存酒店').trigger('click')
    await flushPromises()
    expect(createHotel).toHaveBeenCalledTimes(1)
  })

  it('长度校验按码点计数：码元超限但码点合法的内容不会被误拦', async () => {
    createHotel.mockResolvedValue({ id: '1', status: 'ACTIVE' })
    const wrapper = mountDialog()
    await flushPromises()

    const emojiName = '😀'.repeat(100) // 100 码点 / 200 码元
    const emojiIntro = '😀'.repeat(6000) // 6000 码点 / 12000 码元
    expect(emojiName.length).toBeGreaterThan(128)
    expect(emojiIntro.length).toBeGreaterThan(10000)

    await field(wrapper, '酒店名称').setValue(emojiName)
    await field(wrapper, '城市').setValue('大理')
    await field(wrapper, '酒店简介').setValue(emojiIntro)
    await field(wrapper, '数据来源说明').setValue('团队测试数据')
    await buttonByText(wrapper, '保存酒店').trigger('click')
    await flushPromises()

    expect(createHotel).toHaveBeenCalledTimes(1)
    expect(createHotel.mock.calls[0][0].name).toBe(emojiName)
    expect(createHotel.mock.calls[0][0].intro).toBe(emojiIntro)

    await field(wrapper, '酒店名称').setValue('😀'.repeat(129))
    await field(wrapper, '城市').setValue('大理')
    await buttonByText(wrapper, '保存酒店').trigger('click')
    await flushPromises()
    expect(createHotel).toHaveBeenCalledTimes(1)
  })

  it('后端校验失败时就地展示 message，且不关闭弹窗、不丢用户输入', async () => {
    createHotel.mockRejectedValue(
      Object.assign(new Error('数据来源说明不能为空'), { status: 422, code: 'VALIDATION_ERROR' })
    )
    const wrapper = mountDialog()
    await flushPromises()

    await field(wrapper, '酒店名称').setValue('大理演示酒店')
    await field(wrapper, '城市').setValue('大理')
    await field(wrapper, '数据来源说明').setValue('团队测试数据')
    await buttonByText(wrapper, '保存酒店').trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('数据来源说明不能为空')
    expect(wrapper.emitted('saved')).toBeUndefined()
    expect(field(wrapper, '酒店名称').element.value).toBe('大理演示酒店')
  })
})

describe('HotelFormDialog（契约新增字段）', () => {
  it('存量酒店不猜测城市，补录前阻止保存，补录后提交真实城市', async () => {
    updateHotel.mockResolvedValue({ ...disabledHotel, city: '大理', version: 4 })
    const wrapper = mountDialog({ ...disabledHotel, city: '' })
    await flushPromises()

    expect(field(wrapper, '城市').element.value).toBe('')
    await buttonByText(wrapper, '保存酒店').trigger('click')
    await flushPromises()

    expect(updateHotel).not.toHaveBeenCalled()
    expect(ElMessage.warning).toHaveBeenCalledWith('请填写城市；旧酒店资料需要补录城市后才能保存')
    await field(wrapper, '城市').setValue(' 大理 ')
    await buttonByText(wrapper, '保存酒店').trigger('click')
    await flushPromises()
    expect(updateHotel).toHaveBeenCalledTimes(1)
    expect(updateHotel.mock.calls[0][1].city).toBe('大理')
  })

  it('新建酒店的城市仅含空白时阻止请求，保留用户输入', async () => {
    const wrapper = mountDialog()
    await flushPromises()

    await field(wrapper, '酒店名称').setValue('大理演示酒店')
    await field(wrapper, '城市').setValue('   ')
    await field(wrapper, '数据来源说明').setValue('团队测试数据')
    await buttonByText(wrapper, '保存酒店').trigger('click')
    await flushPromises()

    expect(createHotel).not.toHaveBeenCalled()
    expect(ElMessage.warning).toHaveBeenCalledWith('请填写城市；旧酒店资料需要补录城市后才能保存')
    expect(field(wrapper, '酒店名称').element.value).toBe('大理演示酒店')
  })

  it('官方星级：未选择提交 null，选择后提交数字（不是字符串）', async () => {
    createHotel.mockResolvedValue({ id: '1', status: 'ACTIVE' })
    const wrapper = mountDialog()
    await flushPromises()

    expect(field(wrapper, '官方星级').element.value).toBe('')

    await field(wrapper, '酒店名称').setValue('大理演示酒店')
    await field(wrapper, '城市').setValue('大理')
    await field(wrapper, '数据来源说明').setValue('团队测试数据')
    await field(wrapper, '官方星级').setValue('4')
    await buttonByText(wrapper, '保存酒店').trigger('click')
    await flushPromises()

    expect(createHotel).toHaveBeenCalledTimes(1)
    expect(createHotel.mock.calls[0][0].starRating).toBe(4)
  })

  it('设施限定在契约枚举内且不重复：勾选提交枚举数组，再点一次即取消', async () => {
    createHotel.mockResolvedValue({ id: '1', status: 'ACTIVE' })
    const wrapper = mountDialog()
    await flushPromises()

    const values = wrapper.findAll('.facility-option input').map((input) => input.element.value)
    expect(values).toContain('WIFI')
    expect(values).toContain('ACCESSIBLE_FACILITIES')
    expect(values).toHaveLength(14)

    await facilityCheckbox(wrapper, 'GYM').setValue(true)
    await facilityCheckbox(wrapper, 'SWIMMING_POOL').setValue(true)
    await facilityCheckbox(wrapper, 'GYM').setValue(false)
    await facilityCheckbox(wrapper, 'GYM').setValue(true)

    await field(wrapper, '酒店名称').setValue('大理演示酒店')
    await field(wrapper, '城市').setValue('大理')
    await field(wrapper, '数据来源说明').setValue('团队测试数据')
    await buttonByText(wrapper, '保存酒店').trigger('click')
    await flushPromises()

    const payload = createHotel.mock.calls[0][0]
    expect(payload.facilities).toEqual(['SWIMMING_POOL', 'GYM'])
    expect(new Set(payload.facilities).size).toBe(payload.facilities.length)
  })

  it('入住 / 退房时间：留空提交 null，格式非法时本地拦下，合法 HH:mm 原样提交', async () => {
    createHotel.mockResolvedValue({ id: '1', status: 'ACTIVE' })
    const wrapper = mountDialog()
    await flushPromises()

    await field(wrapper, '酒店名称').setValue('大理演示酒店')
    await field(wrapper, '城市').setValue('大理')
    await field(wrapper, '数据来源说明').setValue('团队测试数据')

    for (const invalid of ['9:00', '24:00', '14:60', '14:00:00', '14时00分']) {
      await field(wrapper, '入住时间').setValue(invalid)
      await buttonByText(wrapper, '保存酒店').trigger('click')
      await flushPromises()
      expect(createHotel).not.toHaveBeenCalled()
    }

    await field(wrapper, '入住时间').setValue('14:00')
    await buttonByText(wrapper, '保存酒店').trigger('click')
    await flushPromises()
    expect(createHotel).toHaveBeenCalledTimes(1)
    expect(createHotel.mock.calls[0][0].checkInTime).toBe('14:00')
    expect(createHotel.mock.calls[0][0].checkOutTime).toBeNull()
  })

  it('图片：url 必须是 http/https 绝对地址，提交时映射成 { url, alt, sortOrder }', async () => {
    createHotel.mockResolvedValue({ id: '1', status: 'ACTIVE' })
    const wrapper = mountDialog()
    await flushPromises()

    await field(wrapper, '酒店名称').setValue('大理演示酒店')
    await field(wrapper, '城市').setValue('大理')
    await field(wrapper, '数据来源说明').setValue('团队测试数据')

    await buttonByText(wrapper, '+ 添加图片').trigger('click')
    await flushPromises()
    expect(wrapper.findAll('.image-row')).toHaveLength(1)

    await wrapper.find('.image-url').setValue('example.com/hotel-1.jpg')
    await buttonByText(wrapper, '保存酒店').trigger('click')
    await flushPromises()
    expect(createHotel).not.toHaveBeenCalled()

    await wrapper.find('.image-url').setValue('ftp://example.com/hotel-1.jpg')
    await buttonByText(wrapper, '保存酒店').trigger('click')
    await flushPromises()
    expect(createHotel).not.toHaveBeenCalled()

    await wrapper.find('.image-url').setValue(' https://example.com/hotel-1.jpg ')
    await wrapper.find('.image-alt').setValue('大堂')
    await wrapper.find('.image-sort').setValue('2')
    await buttonByText(wrapper, '保存酒店').trigger('click')
    await flushPromises()

    expect(createHotel).toHaveBeenCalledTimes(1)
    expect(createHotel.mock.calls[0][0].images).toEqual([
      { url: 'https://example.com/hotel-1.jpg', alt: '大堂', sortOrder: 2 }
    ])
  })

  it('清空全部图片时提交 []（整体替换即清空库内图片记录），并提交 null 封面', async () => {
    updateHotel.mockResolvedValue({ ...disabledHotel, images: [], coverUrl: null })
    const wrapper = mountDialog(disabledHotel)
    await flushPromises()

    expect(wrapper.findAll('.image-row')).toHaveLength(2)
    await buttonByText(wrapper, '删除').trigger('click')
    await buttonByText(wrapper, '删除').trigger('click')
    await flushPromises()
    expect(wrapper.findAll('.image-row')).toHaveLength(0)

    await field(wrapper, '封面图地址').setValue('')
    await buttonByText(wrapper, '保存酒店').trigger('click')
    await flushPromises()

    const payload = updateHotel.mock.calls[0][1]
    expect(payload.images).toEqual([])
    expect(payload.coverUrl).toBeNull()
  })

  it('图片最多 10 张：到达上限后「添加图片」不再增加行', async () => {
    const wrapper = mountDialog({
      ...disabledHotel,
      images: Array.from({ length: 10 }, (_unused, index) => ({
        url: `https://example.com/hotel-31-${index + 1}.jpg`,
        alt: null,
        sortOrder: index + 1
      }))
    })
    await flushPromises()

    expect(wrapper.findAll('.image-row')).toHaveLength(10)
    expect(buttonByText(wrapper, '+ 添加图片').attributes('disabled')).toBeDefined()
    await buttonByText(wrapper, '+ 添加图片').trigger('click')
    await flushPromises()
    expect(wrapper.findAll('.image-row')).toHaveLength(10)
  })
})

describe('酒店新增字段的冲突比较', () => {
  async function conflict(latest) {
    updateHotel.mockRejectedValueOnce(Object.assign(new Error('版本冲突'), { status: 409, code: 'HOTEL_VERSION_CONFLICT' }))
    fetchHotel.mockResolvedValue({ ...disabledHotel, ...latest, version: 4 })
    const wrapper = mountDialog(disabledHotel)
    await flushPromises()
    await buttonByText(wrapper, '保存酒店').trigger('click')
    await flushPromises()
    return wrapper
  }

  it('展示新增字段的服务器值与用户值，避免误报一致', async () => {
    const wrapper = await conflict({ city: '昆明', coverUrl: 'https://example.com/new.jpg',
      images: [{ url: 'https://example.com/new-image.jpg', alt: '新外观', sortOrder: 1 }],
      facilities: ['GYM'], starRating: 5, checkInTime: '15:00', checkOutTime: '11:00' })
    const panel = wrapper.find('.conflict-panel')
    for (const label of ['城市', '封面图', '酒店图片', '酒店设施', '官方星级', '入住时间', '退房时间']) {
      expect(panel.find('.conflict-diff').text()).toContain(label)
    }
    for (const value of ['昆明', 'https://example.com/new.jpg', '新外观', '健身房', '5 星', '15:00', '11:00']) {
      expect(panel.find('.conflict-grid').text()).toContain(value)
    }
    expect(panel.text()).not.toContain('各项与服务器当前值一致')
    expect(field(wrapper, '城市').element.value).toBe('大理')
  })

  it('设施顺序与图片数组顺序不造成假冲突，但图片说明变化会显示差异', async () => {
    const wrapper = await conflict({ facilities: ['PARKING', 'WIFI'], images: [...disabledHotel.images].reverse() })
    expect(wrapper.find('.conflict-diff').text()).toContain('各项与服务器当前值一致')
    wrapper.unmount()
    const changed = await conflict({ images: disabledHotel.images.map((image, index) =>
      ({ ...image, alt: index === 0 ? '修改后的图片说明' : image.alt })) })
    expect(changed.find('.conflict-diff').text()).toContain('酒店图片')
    expect(changed.find('.conflict-grid').text()).toContain('修改后的图片说明')
  })
})

describe('HotelFormDialog（版本冲突面板的差异比较）', () => {
  async function conflictWith(latest, editAddress = true) {
    updateHotel.mockRejectedValueOnce(Object.assign(
      new Error('酒店资料已被他人修改（当前版本 5，你提交的是 3），请查看最新数据后再决定是否覆盖'),
      { status: 409, code: 'HOTEL_VERSION_CONFLICT' }
    ))
    fetchHotel.mockResolvedValue(latest)

    const wrapper = mountDialog(disabledHotel)
    await flushPromises()
    if (editAddress) {
      await field(wrapper, '详细地址').setValue('云南省大理市（我填的）')
    }
    await buttonByText(wrapper, '保存酒店').trigger('click')
    await flushPromises()
    return wrapper
  }

  function diffText(wrapper) {
    const note = wrapper.find('.conflict-diff')
    return note.exists() ? note.text() : ''
  }

  const NO_DIFFERENCE = '你填写的各项与服务器当前值一致'

  it('对方改了城市、封面、星级、设施与入住退房时间时，逐项列进差异而不是说"各项一致"', async () => {
    const wrapper = await conflictWith({
      ...disabledHotel,
      city: '丽江',
      coverUrl: 'https://example.com/other-cover.jpg',
      starRating: 5,
      facilities: ['WIFI', 'PARKING', 'GYM'],
      checkInTime: '15:00',
      checkOutTime: '11:00',
      version: 5
    })

    const text = diffText(wrapper)
    for (const label of ['城市', '封面图', '官方星级', '酒店设施', '入住时间', '退房时间']) {
      expect(text, `差异列表必须包含「${label}」`).toContain(label)
    }
    expect(text).not.toContain(NO_DIFFERENCE)
    expect(wrapper.text()).toContain('丽江')
    expect(wrapper.text()).toContain('5 星')
    expect(wrapper.text()).toContain('健身房')
  })

  it('设施按集合内容比较：只有勾选顺序不同（服务器顺序不同）不算差异', async () => {
    const wrapper = await conflictWith({
      ...disabledHotel,
      facilities: ['PARKING', 'WIFI'],
      version: 5
    }, false)

    expect(diffText(wrapper)).toContain(NO_DIFFERENCE)
    expect(diffText(wrapper)).not.toContain('酒店设施')
  })

  it('设施少一个 / 多一个必须报差异（集合内容变了）', async () => {
    const wrapper = await conflictWith({
      ...disabledHotel,
      facilities: ['WIFI'],
      version: 5
    }, false)

    expect(diffText(wrapper)).toContain('酒店设施')
    expect(diffText(wrapper)).not.toContain(NO_DIFFERENCE)
  })

  it('图片按展示顺序比较：只调换 sortOrder（展示顺序变了）必须报差异', async () => {
    const wrapper = await conflictWith({
      ...disabledHotel,
      images: [
        { url: 'https://example.com/hotel-31-1.jpg', alt: '大堂', sortOrder: 2 },
        { url: 'https://example.com/hotel-31-2.jpg', alt: null, sortOrder: 1 }
      ],
      version: 5
    }, false)

    expect(diffText(wrapper)).toContain('酒店图片')
    expect(diffText(wrapper)).not.toContain(NO_DIFFERENCE)
    expect(wrapper.find('.conflict-grid').text()).toContain('https://example.com/hotel-31-1.jpg')
  })

  it('图片只是数组顺序不同（sortOrder 未变）不算差异，数量变化才算', async () => {
    const reordered = await conflictWith({
      ...disabledHotel,
      images: [
        { url: 'https://example.com/hotel-31-2.jpg', alt: null, sortOrder: 2 },
        { url: 'https://example.com/hotel-31-1.jpg', alt: '大堂', sortOrder: 1 }
      ],
      version: 5
    }, false)
    expect(diffText(reordered)).toContain(NO_DIFFERENCE)
    expect(diffText(reordered)).not.toContain('酒店图片')

    const fewer = await conflictWith({
      ...disabledHotel,
      images: [{ url: 'https://example.com/hotel-31-1.jpg', alt: '大堂', sortOrder: 1 }],
      version: 5
    }, false)
    expect(diffText(fewer)).toContain('酒店图片')
    expect(diffText(fewer)).not.toContain(NO_DIFFERENCE)
  })
})
