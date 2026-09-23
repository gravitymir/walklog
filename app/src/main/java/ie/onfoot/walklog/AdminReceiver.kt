package ie.onfoot.walklog

import android.app.admin.DeviceAdminReceiver

/**
 * Пустой device-admin ресивер: нужен только ради права dpm.lockNow() —
 * погасить и заблокировать экран в момент START, как кнопкой питания.
 */
class AdminReceiver : DeviceAdminReceiver()
