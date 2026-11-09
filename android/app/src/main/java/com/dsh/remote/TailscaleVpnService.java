package com.dsh.remote;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import com.dshgo.app.MainActivity;
import android.app.PendingIntent;
import android.content.Intent;
import android.net.VpnService;
import android.os.Build;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.system.OsConstants;

import com.dshgo.app.data.EventLog;

import java.util.UUID;

/**
 * The in-app VPN service. This is the deep-integration piece: the Tailscale
 * engine runs in our process and establishes the tunnel through this service,
 * so there is never a second VPN app or a foreign UI.
 */
public class TailscaleVpnService extends VpnService implements libtailscale.IPNService {

    public static final String ACTION_START = "com.dsh.remote.START_TUNNEL";
    public static final String ACTION_STOP = "com.dsh.remote.STOP_TUNNEL";
    public static final String ACTION_START_FOREGROUND_ONLY = "com.dsh.remote.START_FOREGROUND_ONLY";
    private static final String CHANNEL = "dsh_tunnel";

    private final String randomID = UUID.randomUUID().toString();
    private static final java.util.concurrent.ExecutorService commands =
        java.util.concurrent.Executors.newSingleThreadExecutor();
    private volatile boolean stopped;

    private android.net.ConnectivityManager.NetworkCallback underlyingCb;
    private final java.util.LinkedHashSet<android.net.Network> physicalNetworks =
        new java.util.LinkedHashSet<>();

    private final NetworkChangePolicy pathPolicy=new NetworkChangePolicy();

    private void updateUnderlyingNetworks() {
        // null delegates selection to Android when no physical network is known.
        // An empty array explicitly tells Android the VPN has NO connectivity.
        android.net.ConnectivityManager cm = (android.net.ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        android.net.Network preferred = SecureStore.physicalNetwork(cm);
        android.net.LinkProperties lp=preferred==null?null:cm.getLinkProperties(preferred);
        android.net.NetworkCapabilities caps=preferred==null?null:cm.getNetworkCapabilities(preferred);
        String dns=lp==null?"":lp.getInterfaceName()+"|"+lp.getDnsServers()+"|"+lp.getDomains();
        int change=pathPolicy.update(preferred==null?0:preferred.getNetworkHandle(),
            caps!=null&&caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED),dns);
        if((change&(NetworkChangePolicy.INITIAL|NetworkChangePolicy.PATH))!=0)
            setUnderlyingNetworks(preferred==null?null:new android.net.Network[]{preferred});
        if((change&(NetworkChangePolicy.INITIAL|NetworkChangePolicy.PATH|NetworkChangePolicy.DNS))!=0)
            libtailscale.Libtailscale.onDNSConfigChanged(lp==null?"":lp.getInterfaceName());
        if(change!=0)Diagnostics.event(this,15,change,0);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        EventLog.event(this, "vpn.service", "create");
        Tunnel.get(this).ensureEngine();
        EventLog.event(this, "vpn.service", "engine ready");
        watchUnderlyingNetwork();
    }

    /** Keep the tunnel bound to the active network (WiFi <-> cellular handover). */
    private void watchUnderlyingNetwork() {
        try {
            android.net.ConnectivityManager cm =
                (android.net.ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            underlyingCb = new android.net.ConnectivityManager.NetworkCallback() {
                @Override public void onAvailable(android.net.Network n) {
                    physicalNetworks.add(n);
                    updateUnderlyingNetworks();
                }

                @Override public void onLost(android.net.Network n) {
                    physicalNetworks.remove(n);
                    updateUnderlyingNetworks();
                }

                @Override public void onCapabilitiesChanged(android.net.Network n, android.net.NetworkCapabilities c) {
                    updateUnderlyingNetworks();
                }

                @Override public void onLinkPropertiesChanged(android.net.Network n, android.net.LinkProperties p) {
                    updateUnderlyingNetworks();
                }
            };
            cm.registerNetworkCallback(
                new android.net.NetworkRequest.Builder()
                    .addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                    .build(), underlyingCb);
        } catch (Exception ignored) {}
    }

    /** 启动失败时收尾：停掉自己，别让异常冒到进程顶层。 */
    private void runCatchingStop() {
        try {
            stopped = true;
            close();
            stopSelf();
        } catch (Throwable ignored) {}
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;
        if (ACTION_STOP.equals(action)) {
            stopped = true;
            commands.execute(() -> Tunnel.get(this).setWantRunning(false));
            close();
            stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_START_FOREGROUND_ONLY.equals(action)) {
            // Keep the engine alive during interactive login without building a
            // tunnel: the control channel stays paused unless a service runs.
            Tunnel.get(this).ensureEngine();
            showNotification();
            return START_NOT_STICKY;
        }
        showNotification();
        stopped = false;
        EventLog.event(this, "vpn.service", "start command, action=" + action);
        commands.execute(() -> {
            try {
                if (stopped) return;
                // 状态机：上一次可能停在半连接（WantRunning=true 但隧道已断），
                // 直接再置 true 会让引擎认为「已经想连了」，establish 永久不触发。
                // 先强制置 false 让引擎回到 idle，再置 true。
                Tunnel t = Tunnel.get(this);
                EventLog.event(this, "engine", "state before=" + t.backendState());
                t.setWantRunning(false);
                try { Thread.sleep(400); } catch (InterruptedException ignored) {}
                if (stopped) return;
                t.setWantRunning(true);
                EventLog.event(this, "engine", "wantRunning=true, requesting vpn");
                if (!stopped) libtailscale.Libtailscale.requestVPN(this);
            } catch (Throwable t) {
                // 引擎/授权失败只记事件并停服务，不要让异常冒到进程顶层变成闪退。
                EventLog.event(this, "engine", "start failed: " + t.getClass().getSimpleName()
                    + " " + (t.getMessage() == null ? "" : t.getMessage()));
                Diagnostics.event(this, 10, 0, 0);
                runCatchingStop();
            }
        });
        return START_STICKY;
    }

    // -------------------------------------------- libtailscale.IPNService

    @Override
    public String id() {
        return randomID;
    }

    @Override
    public boolean protect(int fd) {
        return super.protect(fd);
    }

    @Override
    public libtailscale.VPNServiceBuilder newBuilder() {
        Diagnostics.event(this,8,1,0);
        Builder builder = new Builder()
            .setConfigureIntent(PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE))
            .allowFamily(OsConstants.AF_INET)
            .allowFamily(OsConstants.AF_INET6)
            .addDnsServer("100.100.100.100")
            // 整个 tailnet 网段都要走隧道：只加 100.100.100.100/32 时，
            // 去 100.x 对端设备的流量会绕开隧道走运营商网络，外网就永远连不上。
            .addRoute("100.64.0.0/10", 10)
            .addRoute("100.100.100.100", 32);
        try {
            // Fail closed if the package cannot be selected: never establish
            // an unrestricted VPN that changes other applications' routing.
            builder.addAllowedApplication(getPackageName());
        } catch (android.content.pm.PackageManager.NameNotFoundException e) {
            throw new IllegalStateException("DSHgo VPN application not found", e);
        }
        return new TsBuilder(builder);
    }

    @Override
    public void close() {
        try {
            libtailscale.Libtailscale.serviceDisconnect(this);
        } catch (Exception ignored) {}
    }

    @Override
    public void disconnectVPN() {
        stopSelf();
    }

    @Override
    public void updateVpnStatus(boolean up) {
        // state is surfaced through tunnel notifications; nothing to do here
    }

    // ------------------------------------------------ VpnService events

    @Override
    public void onDestroy() {
        stopped = true;
        if (underlyingCb != null) {
            try {
                ((android.net.ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE))
                    .unregisterNetworkCallback(underlyingCb);
            } catch (Exception ignored) {}
            underlyingCb = null;
        }
        close();
        super.onDestroy();
    }

    @Override
    public void onRevoke() {
        stopped = true;
        commands.execute(() -> Tunnel.get(this).setWantRunning(false));
        close();
        stopSelf();
        super.onRevoke();
    }

    // -------------------------------------------------------- plumbing

    private void showNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm == null) return;
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "DSH 隧道",
                NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }
        PendingIntent pi = PendingIntent.getActivity(this, 0,
            new Intent(this, MainActivity.class),
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
            ? new Notification.Builder(this, CHANNEL)
            : new Notification.Builder(this);
        b.setSmallIcon(com.dshgo.app.R.drawable.ic_notify_f)
            .setContentTitle("DSHgo 专用隧道运行中")
            .setContentText("仅 DSHgo 使用隧道，连接状态请查看主界面")
            .setContentIntent(pi)
            .setOngoing(true);
        try {
            // Android 14+ 起 startForeground 必须带类型，否则抛 MissingForegroundServiceTypeException。
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(42, b.build(),
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            } else {
                startForeground(42, b.build());
            }
            EventLog.event(this, "vpn.fgs", "ok type=specialUse");
        } catch (Throwable t) {
            // 前台通知失败不该让整个 App 崩：隧道即使没有常驻通知也能继续跑一会儿，
            // 真正的问题留给 UI 的「隧道状态」去显示。
            EventLog.event(this, "vpn.fgs", "specialUse failed: " + t.getClass().getSimpleName());
            Diagnostics.event(this, 9, 0, 0);
            // 有些 ROM 对 specialUse 有额外策略，退一步用两参重载（系统按清单里的类型推导）。
            try {
                startForeground(42, b.build());
                EventLog.event(this, "vpn.fgs", "fallback 2-arg ok");
            } catch (Throwable ignored) {
                EventLog.event(this, "vpn.fgs", "fallback 2-arg failed: "
                    + ignored.getClass().getSimpleName());
            }
        }
    }

    /** Wraps android's VpnService.Builder for the Go engine. */
    static final class TsBuilder implements libtailscale.VPNServiceBuilder {
        private final VpnService.Builder b;

        TsBuilder(VpnService.Builder b) {
            this.b = b;
        }

        @Override public void setMTU(int mtu) { b.setMtu(mtu); }
        @Override public void addDNSServer(String s) { b.addDnsServer(s); }
        @Override public void addSearchDomain(String s) { b.addSearchDomain(s); }
        @Override public void addRoute(String addr, int p) { b.addRoute(addr, p); }
        @Override public void excludeRoute(String addr, int p) {
            if (Build.VERSION.SDK_INT >= 33) {
                try {
                    java.net.InetAddress in = java.net.InetAddress.getByName(addr);
                    b.excludeRoute(new android.net.IpPrefix(in, p));
                } catch (Exception ignored) {}
            }
        }
        @Override public void addAddress(String addr, int p) { b.addAddress(addr, p); }
        @Override public libtailscale.ParcelFileDescriptor establish() throws Exception {
            final ParcelFileDescriptor fd = b.establish();
            if (fd == null) return null;
            return new libtailscale.ParcelFileDescriptor() {
                @Override public int detach() throws Exception {
                    return fd.detachFd();
                }
            };
        }
    }
}
