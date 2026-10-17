// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import AdminLayout from '@/layouts/AdminLayout.vue'
const { auth } = vi.hoisted(() => ({ auth: { roles: ['ADMIN'], isLoggedIn: true, user: {} } }))
vi.mock('@/stores/auth', () => ({ useAuthStore: () => ({ ...auth, hasRole: role => auth.roles.includes(role), loadUser: vi.fn(), logout: vi.fn() }) }))
vi.mock('@/utils/scrollRestoration', () => ({ getScrollEntryId: () => 'test', saveScrollPosition: vi.fn(), restoreScrollPosition: vi.fn() }))
import router from '../index'
const wrappers = []
afterEach(() => wrappers.splice(0).forEach(w => w.unmount()))
beforeEach(async () => { window.scrollTo = vi.fn(); auth.roles = ['ADMIN']; await router.push('/') })
describe('后台真实路由守卫', () => {
  it.each(['/admin/users', '/admin/staff', '/admin/logs'])('STAFF 不可直接访问 %s，ADMIN 可访问', async path => {
    auth.roles = ['STAFF']; await router.push(path); expect(router.currentRoute.value.path).toBe('/')
    auth.roles = ['ADMIN']; await router.push(path); expect(router.currentRoute.value.path).toBe(path)
  })
  it('STAFF 可以经营后台但不能进入导游路由', async () => {
    auth.roles = ['STAFF']; await router.push('/admin/orders'); expect(router.currentRoute.value.path).toBe('/admin/orders')
    await router.push('/guide/departures'); expect(router.currentRoute.value.path).toBe('/')
  })
  it('GUIDE 只能进入导游后台而非经营后台', async () => {
    auth.roles = ['GUIDE']; await router.push('/guide/departures'); expect(router.currentRoute.value.path).toBe('/guide/departures')
    await router.push('/admin/orders'); expect(router.currentRoute.value.path).toBe('/')
  })
  it('USER 不能进入任一后台', async () => {
    auth.roles = ['USER']; await router.push('/admin/staff'); expect(router.currentRoute.value.path).toBe('/')
    await router.push('/guide/departures'); expect(router.currentRoute.value.path).toBe('/')
  })
  it.each(['STAFF', 'GUIDE', 'ADMIN'])('%s 菜单与路由权限保持一致', async role => {
    auth.roles = [role]
    await router.push(role === 'GUIDE' ? '/guide/dashboard' : '/admin/dashboard')
    const w = mount(AdminLayout, { global: { plugins: [router], stubs: { RouterView: true } } })
    wrappers.push(w)
    const links = w.findAll('a').map(a => a.attributes('href'))
    for (const path of ['/admin/users', '/admin/staff', '/admin/logs']) {
      expect(links.includes(path)).toBe(role === 'ADMIN')
    }
    expect(links.includes('/guide/departures')).toBe(role === 'GUIDE')
    expect(links.includes('/admin/orders')).toBe(role !== 'GUIDE')
  })
})
