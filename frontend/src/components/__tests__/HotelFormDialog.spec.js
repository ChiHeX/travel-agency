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
    // 版本冲突面板按主键取服务器最新资料（契约 GET /admin/hotels/{hotelId}）
    hotel: (...args) => fetchHotel(...args),
    hotels: (...args) => fetchHotels(...args)
  }
}))

vi.mock('element-plus', () => ({
  ElMessage: { success: vi.fn(), warning: vi.fn(), error: vi.fn(), info: vi.fn() }
}))

/** 已停用的酒店：编辑时表单必须回填 DISABLED，保存时不得把它悄悄改成启用。 */
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

/** 启用的酒店：编辑它但不碰状态时，请求体里不应出现 status（否则会覆盖并发停用）。 */
const activeHotel = { ...disabledHotel, id: '32', name: '杭州湖畔演示酒店', status: 'ACTIVE' }

function mountDialog(hotel = null) {
  return mount(HotelFormDialog, {
    props: { modelValue: true, hotel },
    global: {
      // el-dialog 由应用全局注册，测试里只保留插槽内容；冲突面板里的 el-skeleton 同样跳过。
      stubs: {
        'el-dialog': { template: '<div><slot /><slot name="footer" /></div>' },
        'el-skeleton': true
      }
    }
  })
}

/** 按表单项的 label 定位控件；不能用占位符模糊匹配（名称与地址的占位符高度相似）。 */
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
  // 默认：按主键取详情时资料已不存在（404），各用例按需覆盖成"取到了最新资料"。
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
    // 载荷要说明这次走的是 POST：调用方据此把列表刷到第 1 页（新建记录排在第一页）。
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
    // 未动过状态就不提交它：后端据此保留库内现值，酒店不会被"编辑一下"就启用回来。
    expect(updateHotel.mock.calls[0][1]).not.toHaveProperty('status')
    // 修改必须回传读取时的版本号（契约 HotelUpdateRequest 的乐观锁）。
    expect(updateHotel.mock.calls[0][1].version).toBe(3)
    // 修改走的是 PUT：记录位置不变，调用方不该把列表刷回第 1 页。
    expect(wrapper.emitted('saved')[0][0]).toEqual({
      hotel: { ...disabledHotel, name: '苍山脚下的演示酒店（改名）' },
      created: false
    })
  })

  /**
   * 并发停用的核心回归：管理员只改地址时，请求体里不能带上打开弹窗时读到的旧状态。
   *
   * <p>后端把"未提交 status"定义为保持库内现值；如果每次编辑都顺手提交表单里的旧 ACTIVE，
   * 那么"读旧值 → 另一位管理员停用并提交 → 本事务把 ACTIVE 写回"这条丢失更新就会真的发生。</p>
   */
  it('编辑启用中的酒店：只改地址时不提交 status，改动状态后才提交新值', async () => {
    // 真实后端会回显落库后的状态；用它驱动"保存后再改回来"的那一步。
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
    // 反向：不能因为"要省字段"把地址也省掉。
    expect(untouched).toHaveProperty('name', '杭州湖畔演示酒店')

    // 用户主动停用：必须显式提交 DISABLED，后端才知道这是本次编辑的意图。
    updateHotel.mockClear()
    await wrapper.find('select').setValue('DISABLED')
    await buttonByText(wrapper, '保存酒店').trigger('click')
    await flushPromises()
    expect(updateHotel.mock.calls[0][1].status).toBe('DISABLED')

    // 保存成功后再改回启用：此时的"原始值"应当是服务端刚确认的 DISABLED，而不是打开弹窗时的
    // ACTIVE —— 否则这次改回启用会漏掉 status，界面显示启用而库内仍是停用。
    updateHotel.mockClear()
    await wrapper.find('select').setValue('ACTIVE')
    await buttonByText(wrapper, '保存酒店').trigger('click')
    await flushPromises()
    expect(updateHotel.mock.calls[0][1].status).toBe('ACTIVE')
  })

  /** 新建没有"库内现值"可言：状态是本次建档的明确意图，必须提交（含显式停用）。 */
  it('新增：无论是否改动状态都提交 status，显式选择 DISABLED 时按停用建档', async () => {
    createHotel.mockResolvedValue({ id: '1', status: 'DISABLED' })
    const wrapper = mountDialog()
    await flushPromises()

    await field(wrapper, '酒店名称').setValue('待停用演示酒店')
    await field(wrapper, '数据来源说明').setValue('团队测试数据')
    await wrapper.find('select').setValue('DISABLED')
    await buttonByText(wrapper, '保存酒店').trigger('click')
    await flushPromises()

    expect(createHotel).toHaveBeenCalledTimes(1)
    expect(createHotel.mock.calls[0][0].status).toBe('DISABLED')
    // 建档请求不得带 version（契约 HotelCreateRequest 不含该字段，版本由服务端从 0 起算）。
    expect(createHotel.mock.calls[0][0]).not.toHaveProperty('version')
  })

  // ===================== 乐观锁与版本冲突 =====================

  /**
   * 版本冲突：另一位工作人员在本次编辑期间改过这份资料。
   *
   * <p>关键点是**不丢用户输入**：表单不重载、不清空，只把服务器最新值与差异字段摆在旁边，
   * 由用户决定是采用服务器数据还是覆盖。后端已保证这次提交没有写入任何字段。</p>
   */
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

    // 按主键取详情，而不是拿打开表单时的名称去列表里检索
    expect(fetchHotel).toHaveBeenCalledWith('32')
    expect(fetchHotels).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('已被他人修改')
    expect(wrapper.text()).toContain('你填写的内容已保留')
    expect(wrapper.text()).toContain('详细地址')
    expect(wrapper.text()).toContain('服务器当前版本 5')
    expect(wrapper.text()).toContain('浙江省杭州市西湖区（他人改过）')
    // 用户填写的内容仍在表单里，没有被服务器数据覆盖
    expect(field(wrapper, '详细地址').element.value).toBe('我填的地址')
    // 冲突时不算保存成功，调用方不应刷新成"已保存"
    expect(wrapper.emitted('saved')).toBeUndefined()
    expect(wrapper.emitted('update:modelValue')).toBeUndefined()
  })

  /**
   * 他人改名后发生冲突：这是按名称检索取不到最新资料的典型场景
   * （旧名称已经不在服务器上），而冲突面板必须仍然给出服务器最新数据与出口。
   */
  it('版本冲突：对方已改名时，按主键仍能取到最新资料并完成覆盖', async () => {
    updateHotel
      .mockRejectedValueOnce(Object.assign(new Error('酒店资料已被他人修改'), {
        status: 409, code: 'HOTEL_VERSION_CONFLICT'
      }))
      .mockImplementation(async (id, payload) => ({ ...activeHotel, ...payload, version: 6 }))
    // 服务器上这条资料已经被改成别的名字，地址也变了，版本推进到 5。
    const renamed = { ...activeHotel, name: '杭州湖畔演示酒店（改名后的）', address: '新地址', version: 5 }
    fetchHotel.mockResolvedValue(renamed)

    const wrapper = mountDialog(activeHotel)
    await flushPromises()

    await field(wrapper, '详细地址').setValue('我填的地址')
    await buttonByText(wrapper, '保存酒店').trigger('click')
    await flushPromises()

    // 面板必须展示服务器的最新名称与地址，而不是"取不到最新资料"
    expect(fetchHotel).toHaveBeenCalledWith('32')
    expect(wrapper.text()).toContain('杭州湖畔演示酒店（改名后的）')
    expect(wrapper.text()).toContain('新地址')
    expect(wrapper.text()).not.toContain('暂时取不到服务器最新资料')

    // 出口可用：保留我的修改并覆盖，用服务器最新版本号提交
    await buttonByText(wrapper, '保留我的修改并覆盖').trigger('click')
    await flushPromises()

    const payload = updateHotel.mock.calls.at(-1)[1]
    expect(payload.version).toBe(5)
    expect(payload.address).toBe('我填的地址')
    expect(wrapper.emitted('saved')).toHaveLength(1)
  })

  /** 「载入服务器最新数据」：表单换成服务器版本，之后的保存以最新版本号为基准。 */
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

  /** 「保留我的修改并覆盖」：用服务器最新版本号重新提交用户填写的值。 */
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

  /** 取不到最新资料时不能假装同步过：给出提示，且不提供"盲目覆盖"入口。 */
  it('版本冲突：取不到服务器最新资料时给出提示，且不允许覆盖', async () => {
    updateHotel.mockRejectedValue(Object.assign(new Error('酒店资料已被他人修改'), {
      status: 409, code: 'HOTEL_VERSION_CONFLICT'
    }))
    // 默认桩即"按主键取详情返回 404"（例如资料已被删除）

    const wrapper = mountDialog(activeHotel)
    await flushPromises()

    await buttonByText(wrapper, '保存酒店').trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('暂时取不到服务器最新资料')
    const overwrite = buttonByText(wrapper, '保留我的修改并覆盖')
    expect(overwrite.attributes('disabled')).toBeDefined()
    expect(buttonByText(wrapper, '载入服务器最新数据').attributes('disabled')).toBeDefined()
  })

  /** 取不到最新资料的提示文案要指向真实原因（资料已被删除），而不是笼统的"检索不到"。 */
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
    await field(wrapper, '数据来源说明').setValue('团队测试数据')
    // 联系电话的契约上限是 20 个字符
    await field(wrapper, '联系电话').setValue('1'.repeat(21))
    await buttonByText(wrapper, '保存酒店').trigger('click')
    await flushPromises()
    expect(createHotel).not.toHaveBeenCalled()

    await field(wrapper, '联系电话').setValue('0872-1234567')
    await field(wrapper, '经度').setValue('181')
    await buttonByText(wrapper, '保存酒店').trigger('click')
    await flushPromises()
    expect(createHotel).not.toHaveBeenCalled()

    // 纬度范围是 ±90，经度合法值不能因为复用同一套校验被误拒。
    await field(wrapper, '经度').setValue('100.165')
    await field(wrapper, '纬度').setValue('91')
    await buttonByText(wrapper, '保存酒店').trigger('click')
    await flushPromises()
    expect(createHotel).not.toHaveBeenCalled()

    // 坐标全部合法后应当放行，避免上面的断言因为"永远不发请求"而假通过。
    await field(wrapper, '纬度').setValue('25.694')
    await buttonByText(wrapper, '保存酒店').trigger('click')
    await flushPromises()
    expect(createHotel).toHaveBeenCalledTimes(1)
  })

  it('经纬度必须成对：只填其中一个时不发请求（契约 CoordinatePairRule）', async () => {
    const wrapper = mountDialog()
    await flushPromises()

    await field(wrapper, '酒店名称').setValue('大理演示酒店')
    await field(wrapper, '数据来源说明').setValue('团队测试数据')

    // 只填纬度：单点坐标在用户端地图上无法落点，后端同样会回 422。
    await field(wrapper, '纬度').setValue('25.694')
    await buttonByText(wrapper, '保存酒店').trigger('click')
    await flushPromises()
    expect(createHotel).not.toHaveBeenCalled()
    expect(ElMessage.warning).toHaveBeenCalledWith('经度和纬度需要同时填写，或同时留空')

    // 补齐经度后放行。
    await field(wrapper, '经度').setValue('100.165')
    await buttonByText(wrapper, '保存酒店').trigger('click')
    await flushPromises()
    expect(createHotel).toHaveBeenCalledTimes(1)
  })

  /**
   * 页面校验的边界必须与契约 maxLength 对齐：恰好到上限要放行，多一个字符才拦下。
   * 边界差一位（`>=` 写成 `>` 或反之）会让运营要么永远提交不上、要么把必然 422 的请求发出去。
   */
  it('校验边界与契约 maxLength 一致：恰好到上限放行，超一个字符拦下', async () => {
    createHotel.mockResolvedValue({ id: '1', status: 'ACTIVE' })
    const wrapper = mountDialog()
    await flushPromises()

    await field(wrapper, '酒店名称').setValue('名'.repeat(128))
    await field(wrapper, '联系电话').setValue('1'.repeat(20))
    await field(wrapper, '数据来源说明').setValue('源'.repeat(500))
    await buttonByText(wrapper, '保存酒店').trigger('click')
    await flushPromises()
    expect(createHotel).toHaveBeenCalledTimes(1)

    // 名称多一个字符：必须拦在本地，不再发第二次请求
    await field(wrapper, '酒店名称').setValue('名'.repeat(129))
    await buttonByText(wrapper, '保存酒店').trigger('click')
    await flushPromises()
    expect(createHotel).toHaveBeenCalledTimes(1)

    // 数据来源说明多一个字符同样拦下
    await field(wrapper, '酒店名称').setValue('名'.repeat(128))
    await field(wrapper, '数据来源说明').setValue('源'.repeat(501))
    await buttonByText(wrapper, '保存酒店').trigger('click')
    await flushPromises()
    expect(createHotel).toHaveBeenCalledTimes(1)
  })

  /**
   * 长度上限按 Unicode 码点算（契约 maxLength 的口径，与后端 @CodePointLength 一致），
   * 不是 JS 的 UTF-16 码元：用 String#length 会把 100 个 emoji 的酒店名算成 200 而误拦。
   */
  it('长度校验按码点计数：码元超限但码点合法的内容不会被误拦', async () => {
    createHotel.mockResolvedValue({ id: '1', status: 'ACTIVE' })
    const wrapper = mountDialog()
    await flushPromises()

    const emojiName = '😀'.repeat(100) // 100 码点 / 200 码元
    const emojiIntro = '😀'.repeat(6000) // 6000 码点 / 12000 码元
    expect(emojiName.length).toBeGreaterThan(128)
    expect(emojiIntro.length).toBeGreaterThan(10000)

    await field(wrapper, '酒店名称').setValue(emojiName)
    await field(wrapper, '酒店简介').setValue(emojiIntro)
    await field(wrapper, '数据来源说明').setValue('团队测试数据')
    await buttonByText(wrapper, '保存酒店').trigger('click')
    await flushPromises()

    expect(createHotel).toHaveBeenCalledTimes(1)
    expect(createHotel.mock.calls[0][0].name).toBe(emojiName)
    expect(createHotel.mock.calls[0][0].intro).toBe(emojiIntro)

    // 超出码点上限（129 > 128）仍然要拦下，别把"按码点算"做成"不校验"
    await field(wrapper, '酒店名称').setValue('😀'.repeat(129))
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
    await field(wrapper, '数据来源说明').setValue('团队测试数据')
    await buttonByText(wrapper, '保存酒店').trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('数据来源说明不能为空')
    expect(wrapper.emitted('saved')).toBeUndefined()
    expect(field(wrapper, '酒店名称').element.value).toBe('大理演示酒店')
  })
})

describe('HotelFormDialog（契约新增字段）', () => {
  it('编辑存量资料：city 是迁移脚本补的空串时照常提交空串，由后端 422 提示补录', async () => {
    updateHotel.mockResolvedValue({ ...disabledHotel, city: '' })
    const wrapper = mountDialog({ ...disabledHotel, city: '' })
    await flushPromises()

    expect(field(wrapper, '城市').element.value).toBe('')
    await buttonByText(wrapper, '保存酒店').trigger('click')
    await flushPromises()

    expect(updateHotel).toHaveBeenCalledTimes(1)
    expect(updateHotel.mock.calls[0][1].city).toBe('')
  })

  it('后端对空城市的 422 message 就地展示，用户输入不丢', async () => {
    createHotel.mockRejectedValue(
      Object.assign(new Error('城市不能为空'), { status: 422, code: 'VALIDATION_ERROR' })
    )
    const wrapper = mountDialog()
    await flushPromises()

    await field(wrapper, '酒店名称').setValue('大理演示酒店')
    await field(wrapper, '数据来源说明').setValue('团队测试数据')
    await buttonByText(wrapper, '保存酒店').trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('城市不能为空')
    expect(field(wrapper, '酒店名称').element.value).toBe('大理演示酒店')
  })

  it('官方星级：未选择提交 null，选择后提交数字（不是字符串）', async () => {
    createHotel.mockResolvedValue({ id: '1', status: 'ACTIVE' })
    const wrapper = mountDialog()
    await flushPromises()

    expect(field(wrapper, '官方星级').element.value).toBe('')

    await field(wrapper, '酒店名称').setValue('大理演示酒店')
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
