.class final Lcom/kia/yandex/v2/YandexKiaBridgeHeartbeat;
.super Ljava/lang/Object;
.source "YandexKiaBridgeHeartbeat.java"

# interfaces
.implements Ljava/lang/Runnable;


# direct methods
.method constructor <init>()V
    .locals 0

    invoke-direct {p0}, Ljava/lang/Object;-><init>()V

    return-void
.end method


# virtual methods
.method public run()V
    .locals 1

    const-string v0, "heartbeat"

    invoke-static {v0}, Lcom/kia/yandex/v2/YandexKiaBridge;->publish(Ljava/lang/String;)V

    invoke-static {}, Lcom/kia/yandex/v2/YandexKiaBridge;->scheduleHeartbeat()V

    return-void
.end method
