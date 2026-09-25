import { createRouter, createWebHistory } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import PublicLayout from '@/layouts/PublicLayout.vue'
import AdminLayout from '@/layouts/AdminLayout.vue'
import HomeView from '@/views/public/HomeView.vue'
import SearchView from '@/views/public/SearchView.vue'
import RouteListView from '@/views/public/RouteListView.vue'
import RouteDetailView from '@/views/public/RouteDetailView.vue'
import LoginView from '@/views/public/LoginView.vue'
import RegisterView from '@/views/public/RegisterView.vue'
import ArticlesView from '@/views/public/ArticlesView.vue'
import GuidesView from '@/views/public/GuidesView.vue'
import LatestGuidesView from '@/views/public/LatestGuidesView.vue'
import CityGuidesView from '@/views/public/CityGuidesView.vue'
import GuidePublishersView from '@/views/public/GuidePublishersView.vue'
import PublisherGuidesView from '@/views/public/PublisherGuidesView.vue'
import PlaceGuideDetailView from '@/views/public/PlaceGuideDetailView.vue'
import ArticleDetailView from '@/views/public/ArticleDetailView.vue'
import AttractionDetailView from '@/views/public/AttractionDetailView.vue'
import OrderCreateView from '@/views/public/OrderCreateView.vue'
import OrdersView from '@/views/account/OrdersView.vue'
import OrderDetailView from '@/views/account/OrderDetailView.vue'
import PaymentView from '@/views/account/PaymentView.vue'
import PaymentResultView from '@/views/account/PaymentResultView.vue'
import AccountView from '@/views/account/AccountView.vue'
import ReviewsView from '@/views/account/ReviewsView.vue'
import SecurityView from '@/views/account/SecurityView.vue'
import TravelersView from '@/views/account/TravelersView.vue'
import FavoritesView from '@/views/account/FavoritesView.vue'
import MessagesView from '@/views/account/MessagesView.vue'
import ConsultationView from '@/views/account/ConsultationView.vue'
import AdminDashboardView from '@/views/admin/AdminDashboardView.vue'
import AdminRoutesView from '@/views/admin/AdminRoutesView.vue'
import AdminRouteDetailView from '@/views/admin/AdminRouteDetailView.vue'
import AdminOrdersView from '@/views/admin/AdminOrdersView.vue'
import AdminResourcesView from '@/views/admin/AdminResourcesView.vue'
import AdminUsersView from '@/views/admin/AdminUsersView.vue'
import GuideDashboardView from '@/views/guide/GuideDashboardView.vue'
import GuideTripsView from '@/views/guide/GuideTripsView.vue'
import ComingSoonView from '@/views/ComingSoonView.vue'
import { getScrollEntryId, restoreScrollPosition, saveScrollPosition } from '@/utils/scrollRestoration'

const routes = [
  {
    path: '/',
    component: PublicLayout,
    children: [
      { path: '', name: 'home', component: HomeView },
      { path: 'search', name: 'search', component: SearchView },
      { path: 'routes', name: 'routes', component: RouteListView },
      { path: 'routes/:id', name: 'route-detail', component: RouteDetailView },
      { path: 'guides', name: 'guides', component: GuidesView },
      { path: 'guides/latest', name: 'latest-guides', component: LatestGuidesView },
      { path: 'guides/cities/:city', name: 'city-guides', component: CityGuidesView },
      { path: 'guides/publishers', name: 'guide-publishers', component: GuidePublishersView },
      { path: 'guides/publishers/:id', name: 'publisher-guides', component: PublisherGuidesView },
      { path: 'guides/:id', name: 'guide-detail', component: PlaceGuideDetailView },
      { path: 'articles', name: 'articles', component: ArticlesView },
      { path: 'articles/latest', redirect: { name: 'articles' } },
      { path: 'articles/cities/:city', redirect: (to) => ({ name: 'articles', query: { destination: to.params.city } }) },
      { path: 'articles/publishers', redirect: { name: 'articles' } },
      { path: 'articles/publishers/:id', redirect: { name: 'articles' } },
      { path: 'articles/:id', name: 'article-detail', component: ArticleDetailView },
      { path: 'attractions/:id', name: 'attraction-detail', component: AttractionDetailView },
      { path: 'booking', name: 'order-create', component: OrderCreateView, meta: { requiresAuth: true } },
      { path: 'auth/login', name: 'login', component: LoginView },
      { path: 'auth/register', name: 'register', component: RegisterView }
    ]
  },
  {
    path: '/account',
    component: PublicLayout,
    meta: { requiresAuth: true },
    children: [
      { path: '', redirect: { name: 'account-profile' } },
      { path: 'profile', name: 'account-profile', component: AccountView },
      { path: 'reviews', name: 'account-reviews', component: ReviewsView },
      { path: 'security', name: 'account-security', component: SecurityView },
      { path: 'orders', name: 'account-orders', component: OrdersView },
      { path: 'orders/:orderNo/payment', name: 'order-payment', component: PaymentView },
      { path: 'orders/:orderNo/payment/result', name: 'order-payment-result', component: PaymentResultView },
      { path: 'orders/:orderNo', name: 'order-detail', component: OrderDetailView },
      { path: 'order/create', redirect: (to) => ({ name: 'order-create', query: to.query }) },
      { path: 'travelers', name: 'account-travelers', component: TravelersView },
      { path: 'favorites', name: 'account-favorites', component: FavoritesView },
      { path: 'messages', name: 'account-messages', component: MessagesView },
      { path: 'consultations', name: 'account-consultations', component: ConsultationView }
    ]
  },
  {
    path: '/admin',
    component: AdminLayout,
    meta: { requiresAuth: true, roles: ['ADMIN', 'STAFF'] },
    children: [
      { path: '', redirect: { name: 'admin-dashboard' } },
      { path: 'dashboard', name: 'admin-dashboard', component: AdminDashboardView },
      { path: 'routes', name: 'admin-routes', component: AdminRoutesView },
      { path: 'routes/:id', name: 'admin-route-detail', component: AdminRouteDetailView },
      { path: 'departures', name: 'admin-departures', component: AdminResourcesView, props: { title: '团期管理', resource: 'departures' } },
      { path: 'attractions', name: 'admin-attractions', component: AdminResourcesView, props: { title: '景点管理', resource: 'attractions' } },
      { path: 'hotels', name: 'admin-hotels', component: AdminResourcesView, props: { title: '酒店资料', resource: 'hotels' } },
      { path: 'guides', name: 'admin-guides', component: AdminResourcesView, props: { title: '导游管理', resource: 'guides' } },
      { path: 'orders', name: 'admin-orders', component: AdminOrdersView },
      { path: 'refunds', name: 'admin-refunds', component: AdminResourcesView, props: { title: '退款审核', resource: 'refunds' } },
      { path: 'users', name: 'admin-users', component: AdminUsersView },
      { path: 'staff', name: 'admin-staff', component: AdminUsersView, props: { mode: 'staff' } },
      { path: 'reviews', name: 'admin-reviews', component: ComingSoonView, props: { title: '评价管理' } },
      { path: 'consultations', name: 'admin-consultations', component: ComingSoonView, props: { title: '在线咨询' } },
      { path: 'articles', name: 'admin-articles', component: ComingSoonView, props: { title: '旅游攻略管理' } },
      { path: 'logs', name: 'admin-logs', component: ComingSoonView, props: { title: '操作日志' } }
    ]
  },
  {
    path: '/guide',
    component: AdminLayout,
    meta: { requiresAuth: true, roles: ['GUIDE', 'ADMIN'] },
    children: [
      { path: '', redirect: { name: 'guide-dashboard' } },
      { path: 'dashboard', name: 'guide-dashboard', component: GuideDashboardView },
      { path: 'departures', name: 'guide-departures', component: GuideTripsView },
      { path: 'departures/:id', name: 'guide-trip-detail', component: GuideTripsView, props: { detail: true } }
    ]
  },
  { path: '/:pathMatch(.*)*', redirect: '/' }
]

const router = createRouter({
  history: createWebHistory(),
  routes,
  scrollBehavior: (_to, _from, savedPosition) => savedPosition || { top: 0 }
})

let activeScrollEntryId = getScrollEntryId()

router.beforeEach(() => {
  saveScrollPosition(activeScrollEntryId)
})

router.afterEach((_to, _from, failure) => {
  if (failure) return
  activeScrollEntryId = getScrollEntryId()
  restoreScrollPosition(activeScrollEntryId)
})

router.beforeEach(async (to) => {
  const auth = useAuthStore()
  if (auth.isLoggedIn && !auth.user) await auth.loadUser()
  if (to.meta.requiresAuth && !auth.isLoggedIn) {
    return { name: 'login', query: { redirect: to.fullPath } }
  }
  const allowedRoles = to.meta.roles
  if (allowedRoles && !allowedRoles.some((role) => auth.hasRole(role))) {
    return auth.isLoggedIn ? { name: 'home' } : { name: 'login' }
  }
  return true
})

window.addEventListener('travel-auth-expired', () => {
  useAuthStore().logout()
  const current = router.currentRoute.value
  if (current.meta.requiresAuth) router.replace({ name: 'login', query: { redirect: current.fullPath } })
})

export default router
