import request from './request'

function createIdempotencyKey() {
  if (globalThis.crypto?.randomUUID) return globalThis.crypto.randomUUID()
  return `web-${Date.now()}-${Math.random().toString(36).slice(2)}`
}

function idempotentConfig(idempotencyKey = createIdempotencyKey()) {
  return { headers: { 'Idempotency-Key': idempotencyKey } }
}

export const systemApi = {
  health: () => request.get('/health')
}

export const authApi = {
  login: (payload) => request.post('/auth/login', payload),
  register: (payload) => request.post('/auth/register', payload),
  me: () => request.get('/auth/me'),
  profile: () => request.get('/account/profile'),
  updateProfile: (payload) => request.put('/account/profile', payload),
  changePassword: (payload) => request.put('/account/password', payload)
}

export const homeApi = {
  get: () => request.get('/home')
}

export const routeApi = {
  list: (params) => request.get('/routes', { params }),
  detail: (routeId) => request.get(`/routes/${routeId}`),
  reviews: (routeId, params) => request.get(`/routes/${routeId}/reviews`, { params })
}

export const orderApi = {
  list: (params) => request.get('/orders', { params }),
  detail: (orderNo) => request.get(`/orders/${orderNo}`),
  create: (payload, idempotencyKey) => request.post('/orders', payload, idempotentConfig(idempotencyKey)),
  pay: (orderNo, idempotencyKey) => request.post(`/orders/${orderNo}/pay`, null, idempotentConfig(idempotencyKey)),
  cancel: (orderNo) => request.post(`/orders/${orderNo}/cancel`),
  refund: (orderNo, payload, idempotencyKey) =>
    request.post(`/orders/${orderNo}/refunds`, payload, idempotentConfig(idempotencyKey)),
  review: (orderNo, payload) => request.post(`/orders/${orderNo}/reviews`, payload)
}

export const accountApi = {
  travelers: () => request.get('/travelers'),
  createTraveler: (payload) => request.post('/travelers', payload),
  updateTraveler: (travelerId, payload) => request.put(`/travelers/${travelerId}`, payload),
  deleteTraveler: (travelerId) => request.delete(`/travelers/${travelerId}`),
  favorites: (params) => request.get('/favorites', { params }),
  addFavorite: (routeId) => request.post('/favorites', { routeId: String(routeId) }),
  removeFavorite: (routeId) => request.delete(`/favorites/${routeId}`),
  messages: (params) => request.get('/messages', { params }),
  unreadCount: () => request.get('/messages/unread-count'),
  readMessage: (messageId) => request.patch(`/messages/${messageId}/read`),
  readAllMessages: () => request.post('/messages/read-all'),
  consultations: (params) => request.get('/consultations', { params }),
  consultation: (consultationId) => request.get(`/consultations/${consultationId}`),
  createConsultation: (payload) => request.post('/consultations', payload),
  closeConsultation: (consultationId) => request.post(`/consultations/${consultationId}/close`)
}

export const contentApi = {
  articles: (params) => request.get('/articles', { params }),
  article: (articleId) => request.get(`/articles/${articleId}`),
  attractions: (params) => request.get('/attractions', { params })
}

export const adminApi = {
  dashboard: (params) => request.get('/admin/dashboard', { params }),

  routes: (params) => request.get('/admin/routes', { params }),
  route: (routeId) => request.get(`/admin/routes/${routeId}`),
  createRoute: (payload) => request.post('/admin/routes', payload),
  updateRoute: (routeId, payload) => request.put(`/admin/routes/${routeId}`, payload),
  updateRouteStatus: (routeId, status) => request.patch(`/admin/routes/${routeId}/status`, { status }),
  itineraryDays: (routeId) => request.get(`/admin/routes/${routeId}/itinerary-days`),
  createItineraryDay: (routeId, payload) => request.post(`/admin/routes/${routeId}/itinerary-days`, payload),
  updateItineraryDay: (dayId, payload) => request.put(`/admin/itinerary-days/${dayId}`, payload),
  deleteItineraryDay: (dayId) => request.delete(`/admin/itinerary-days/${dayId}`),
  itineraryItems: (dayId) => request.get(`/admin/itinerary-days/${dayId}/items`),
  createItineraryItem: (dayId, payload) => request.post(`/admin/itinerary-days/${dayId}/items`, payload),
  updateItineraryItem: (itemId, payload) => request.put(`/admin/itinerary-items/${itemId}`, payload),
  deleteItineraryItem: (itemId) => request.delete(`/admin/itinerary-items/${itemId}`),

  departures: (params) => request.get('/admin/departures', { params }),
  departure: (departureId) => request.get(`/admin/departures/${departureId}`),
  createDeparture: (payload) => request.post('/admin/departures', payload),
  updateDeparture: (departureId, payload) => request.put(`/admin/departures/${departureId}`, payload),
  updateDepartureStatus: (departureId, status) =>
    request.patch(`/admin/departures/${departureId}/status`, { status }),

  attractions: (params) => request.get('/admin/attractions', { params }),
  createAttraction: (payload) => request.post('/admin/attractions', payload),
  updateAttraction: (attractionId, payload) => request.put(`/admin/attractions/${attractionId}`, payload),
  deleteAttraction: (attractionId) => request.delete(`/admin/attractions/${attractionId}`),
  hotels: (params) => request.get('/admin/hotels', { params }),
  createHotel: (payload) => request.post('/admin/hotels', payload),
  updateHotel: (hotelId, payload) => request.put(`/admin/hotels/${hotelId}`, payload),
  deleteHotel: (hotelId) => request.delete(`/admin/hotels/${hotelId}`),
  guides: (params) => request.get('/admin/guides', { params }),
  guide: (guideId) => request.get(`/admin/guides/${guideId}`),
  createGuide: (payload) => request.post('/admin/guides', payload),
  updateGuide: (guideId, payload) => request.put(`/admin/guides/${guideId}`, payload),
  updateGuideStatus: (guideId, status) => request.patch(`/admin/guides/${guideId}/status`, { status }),

  orders: (params) => request.get('/admin/orders', { params }),
  order: (orderNo) => request.get(`/admin/orders/${orderNo}`),
  confirmOrder: (orderNo) => request.post(`/admin/orders/${orderNo}/confirm`),
  refunds: (params) => request.get('/admin/refunds', { params }),
  refund: (refundId) => request.get(`/admin/refunds/${refundId}`),
  approveRefund: (refundId, comment = '') => request.post(`/admin/refunds/${refundId}/approve`, { comment }),
  rejectRefund: (refundId, comment) => request.post(`/admin/refunds/${refundId}/reject`, { comment }),

  reviews: (params) => request.get('/admin/reviews', { params }),
  updateReviewStatus: (reviewId, status) => request.patch(`/admin/reviews/${reviewId}/status`, { status }),
  users: (params) => request.get('/admin/users', { params }),
  user: (userId) => request.get(`/admin/users/${userId}`),
  updateUserStatus: (userId, status) => request.patch(`/admin/users/${userId}/status`, { status }),
  staff: (params) => request.get('/admin/staff', { params }),
  createStaff: (payload) => request.post('/admin/staff', payload),
  updateStaff: (staffId, payload) => request.put(`/admin/staff/${staffId}`, payload),
  updateStaffStatus: (staffId, status) => request.patch(`/admin/staff/${staffId}/status`, { status }),

  consultations: (params) => request.get('/admin/consultations', { params }),
  consultation: (consultationId) => request.get(`/admin/consultations/${consultationId}`),
  replyConsultation: (consultationId, payload) =>
    request.post(`/admin/consultations/${consultationId}/replies`, payload),
  closeConsultation: (consultationId) => request.post(`/admin/consultations/${consultationId}/close`),
  articles: (params) => request.get('/admin/articles', { params }),
  article: (articleId) => request.get(`/admin/articles/${articleId}`),
  createArticle: (payload) => request.post('/admin/articles', payload),
  updateArticle: (articleId, payload) => request.put(`/admin/articles/${articleId}`, payload),
  deleteArticle: (articleId) => request.delete(`/admin/articles/${articleId}`),
  updateArticleStatus: (articleId, status) => request.patch(`/admin/articles/${articleId}/status`, { status }),
  logs: (params) => request.get('/admin/logs', { params })
}

export const guideApi = {
  dashboard: () => request.get('/guide/dashboard'),
  departures: (params) => request.get('/guide/departures', { params }),
  detail: (departureId) => request.get(`/guide/departures/${departureId}`),
  passengers: (departureId) => request.get(`/guide/departures/${departureId}/passengers`),
  start: (departureId) => request.post(`/guide/departures/${departureId}/start`),
  complete: (departureId) => request.post(`/guide/departures/${departureId}/complete`)
}
