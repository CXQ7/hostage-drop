<script setup>
import { Client } from "@stomp/stompjs";
import { computed, nextTick, onBeforeUnmount, reactive, ref, watch } from "vue";
import { ElMessage } from "element-plus";

const gatewayBase = ref("http://localhost:8080");
const stompClient = ref(null);
const blindBoxTimer = ref(null);
const activeComposer = ref("initiate");
const isConnected = ref(false);
const chatMessages = ref([]);
const seenBaitTxIds = new Set();
const seenEventKeys = new Set();
const messageStream = ref(null);
const pendingInitiate = ref(null);
const pendingPay = ref(null);
const maxUploadBytes = 10 * 1024 * 1024;
const historyLoaded = ref(false);

const activeBait = reactive({
  txId: "",
  senderId: "",
});

const session = reactive({
  userId: "B",
  peerId: "A",
  wsStatus: "未开始",
});

const initiateForm = reactive({
  senderId: "A",
  receiverId: "B",
  textContent: "",
  ttlSeconds: 180,
  fileName: "",
  fileContentBase64: "",
  filePreviewUrl: "",
});

const payForm = reactive({
  txId: "",
  payerId: "B",
  payeeId: "A",
  textContent: "",
  ransomFileName: "",
  ransomFileContentBase64: "",
  ransomPreviewUrl: "",
});

const blindBox = reactive({
  visible: false,
  txId: "",
  senderId: "",
  fileName: "",
  expireAt: 0,
  countdownLabel: "等待通知...",
});

const initiateFileList = ref([]);
const payFileList = ref([]);

const peerLabel = computed(() => {
  return blindBox.senderId || session.peerId || "未指定对象";
});

const onlineTagType = computed(() => (isConnected.value ? "success" : "info"));
const wsUrl = computed(() => `${gatewayBase.value.replace(/^http/, "ws")}/ws`);

function isImage(dataUrl) {
  return typeof dataUrl === "string" && dataUrl.startsWith("data:image");
}

function nowLabel() {
  return new Date().toLocaleTimeString();
}

function extractLastBaitContext(items) {
  const list = Array.isArray(items) ? items : [];
  for (let i = list.length - 1; i >= 0; i -= 1) {
    const item = list[i];
    if (item?.type === "bait" && item?.txId && item?.senderId) {
      return { txId: item.txId, senderId: item.senderId };
    }
  }
  return null;
}

function loadHistory(items) {
  chatMessages.value = (items || []).map((entry) => ({
    id: `${Date.now()}-${Math.random().toString(36).slice(2, 8)}`,
    time: entry.time || nowLabel(),
    side: entry.side || "center",
    type: entry.type || "system",
    title: entry.title || "历史消息",
    fileName: entry.fileName || "",
    text: entry.text || "",
    imageUrl: entry.imageUrl || "",
    txId: entry.txId || "",
    senderId: entry.senderId || "",
    txStatus: entry.txStatus || "",
  }));

  const baitContext = extractLastBaitContext(items);
  if (baitContext) {
    activeBait.txId = baitContext.txId;
    activeBait.senderId = baitContext.senderId;
    payForm.txId = baitContext.txId;
    payForm.payeeId = baitContext.senderId;
  }

  historyLoaded.value = true;
  scrollToBottom();
}

function scrollToBottom() {
  nextTick(() => {
    if (messageStream.value) {
      messageStream.value.scrollTop = messageStream.value.scrollHeight;
    }
  });
}

function pushBubble(entry) {
  chatMessages.value.push({
    id: `${Date.now()}-${Math.random().toString(36).slice(2, 8)}`,
    time: nowLabel(),
    ...entry,
  });
  scrollToBottom();
}

function clearBlindBox() {
  blindBox.visible = false;
  blindBox.txId = "";
  blindBox.senderId = "";
  blindBox.fileName = "";
  blindBox.expireAt = 0;
  blindBox.countdownLabel = "等待通知...";
  if (blindBoxTimer.value) {
    clearInterval(blindBoxTimer.value);
    blindBoxTimer.value = null;
  }
}

function dismissBlindBox() {
  blindBox.visible = false;
  if (blindBoxTimer.value) {
    clearInterval(blindBoxTimer.value);
    blindBoxTimer.value = null;
  }
}

function startBlindBoxCountdown(expireAt) {
  blindBox.expireAt = Number(expireAt);
  if (blindBoxTimer.value) {
    clearInterval(blindBoxTimer.value);
  }

  const tick = () => {
    const leftSec = Math.floor((blindBox.expireAt - Date.now()) / 1000);
    if (leftSec <= 0) {
      pushBubble({
        side: "center",
        type: "system",
        title: "盲盒已超时",
        text: "Watchdog 到达绝对过期时间，弹窗已自动销毁。",
      });
      clearBlindBox();
      return;
    }
    blindBox.countdownLabel = `剩余 ${leftSec}s · 绝对过期 ${new Date(blindBox.expireAt).toLocaleTimeString()}`;
  };

  tick();
  blindBoxTimer.value = setInterval(tick, 1000);
}

function readFileAsDataUrl(rawFile) {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => resolve(String(reader.result));
    reader.onerror = () => reject(new Error("文件读取失败"));
    reader.readAsDataURL(rawFile);
  });
}

async function onInitiateFileChange(uploadFile) {
  if (!uploadFile.raw) {
    return false;
  }
  if (uploadFile.raw.size > maxUploadBytes) {
    ElMessage.warning("文件过大，请控制在 10MB 以内");
    return false;
  }
  const dataUrl = await readFileAsDataUrl(uploadFile.raw);
  initiateForm.fileName = uploadFile.name;
  initiateForm.fileContentBase64 = dataUrl;
  initiateForm.filePreviewUrl = dataUrl;
  initiateFileList.value = [uploadFile];
  return false;
}

async function onPayFileChange(uploadFile) {
  if (!uploadFile.raw) {
    return false;
  }
  if (uploadFile.raw.size > maxUploadBytes) {
    ElMessage.warning("文件过大，请控制在 10MB 以内");
    return false;
  }
  const dataUrl = await readFileAsDataUrl(uploadFile.raw);
  payForm.ransomFileName = uploadFile.name;
  payForm.ransomFileContentBase64 = dataUrl;
  payForm.ransomPreviewUrl = dataUrl;
  payFileList.value = [uploadFile];
  return false;
}

function removeInitiateFile() {
  initiateFileList.value = [];
  initiateForm.fileName = "";
  initiateForm.fileContentBase64 = "";
  initiateForm.filePreviewUrl = "";
}

function removePayFile() {
  payFileList.value = [];
  payForm.ransomFileName = "";
  payForm.ransomFileContentBase64 = "";
  payForm.ransomPreviewUrl = "";
}

function subscribeUserChannels(client) {
  client.subscribe("/user/queue/events", (frame) => {
    const payload = JSON.parse(frame.body);
    handleGatewayEvent(payload);
  });

  client.subscribe("/user/queue/acks", (frame) => {
    const payload = JSON.parse(frame.body);
    handleAck(payload);
  });

  client.subscribe("/user/queue/errors", (frame) => {
    const payload = JSON.parse(frame.body);
    ElMessage.error(payload.message || "网关处理失败");
    pushBubble({
      side: "center",
      type: "system",
      title: "网关错误",
      text: payload.message || "未知错误",
    });
  });

  client.subscribe("/user/queue/history", (frame) => {
    const payload = JSON.parse(frame.body || "{}");
    if (payload.type === "HISTORY_SNAPSHOT") {
      loadHistory(payload.items || []);
    }
  });

  client.publish({ destination: "/app/ready", body: "{}" });
  client.publish({ destination: "/app/history", body: "{}" });
}

function connectGateway() {
  if (!session.userId.trim()) {
    ElMessage.warning("请填写当前用户 ID");
    return;
  }

  if (stompClient.value?.active) {
    stompClient.value.deactivate();
  }

  const client = new Client({
    brokerURL: wsUrl.value,
    reconnectDelay: 3000,
    heartbeatIncoming: 10000,
    heartbeatOutgoing: 10000,
    connectHeaders: {
      userId: session.userId.trim(),
    },
  });

  client.onConnect = () => {
    stompClient.value = client;
    isConnected.value = true;
    historyLoaded.value = false;
    // Keep form identities aligned with the currently logged-in user.
    initiateForm.senderId = session.userId.trim();
    payForm.payerId = session.userId.trim();
    initiateForm.receiverId = session.peerId.trim();
    session.wsStatus = `已连接为 ${session.userId.trim()}`;
    subscribeUserChannels(client);
    pushBubble({
      side: "center",
      type: "system",
      title: "会话已开启",
      text: `实时通道已建立，当前身份 ${session.userId.trim()}。`,
      transient: true,
    });
    ElMessage.success("会话通道已连接");
  };

  client.onStompError = (frame) => {
    ElMessage.error(frame.headers.message || "通道连接异常");
  };

  client.onWebSocketClose = (event) => {
    isConnected.value = false;
    session.wsStatus = "连接已断开";
    if (event?.code) {
      ElMessage.error(`连接已断开（code=${event.code}）`);
    }
  };

  client.activate();
}

function switchPeer() {
  const peer = session.peerId.trim();
  if (!peer) {
    ElMessage.warning("请先填写对话对象 ID");
    return;
  }

  initiateForm.receiverId = peer;
  ElMessage.success(`已切换对话对象为 ${peer}`);
}

function publishMessage(destination, payload) {
  if (!stompClient.value?.connected) {
    ElMessage.warning("请先开始会话");
    return false;
  }
  stompClient.value.publish({
    destination,
    body: JSON.stringify(payload),
  });
  return true;
}

function initiateTrade() {
  if (!initiateForm.textContent.trim() && !initiateForm.fileContentBase64) {
    ElMessage.warning("请填写盲盒文字或上传附件");
    return;
  }

  const ok = publishMessage("/app/initiate", {
    senderId: initiateForm.senderId.trim(),
    receiverId: initiateForm.receiverId.trim(),
    textContent: initiateForm.textContent.trim(),
    fileName: initiateForm.fileName,
    fileContentBase64: initiateForm.fileContentBase64,
    ttlSeconds: Number(initiateForm.ttlSeconds || 180),
  });

  if (!ok) {
    return;
  }

  pendingInitiate.value = {
    receiverId: initiateForm.receiverId,
    fileName: initiateForm.fileName,
    imageUrl: initiateForm.filePreviewUrl,
    textContent: initiateForm.textContent,
    ttlSeconds: initiateForm.ttlSeconds,
  };
  ElMessage.info("盲盒请求已发送，等待网关确认");
}

function payRansom() {
  if (!payForm.textContent.trim() && !payForm.ransomFileContentBase64) {
    ElMessage.warning("请填写赎金文字或上传附件");
    return;
  }

  const resolvedTxId = payForm.txId.trim() || activeBait.txId;
  const resolvedPayeeId = activeBait.senderId || payForm.payeeId.trim();

  if (!resolvedTxId || !resolvedPayeeId) {
    ElMessage.warning("缺少交易上下文，请先选择有效盲盒后再支付");
    return;
  }

  if (activeBait.txId && resolvedTxId !== activeBait.txId) {
    ElMessage.warning("支付交易号与当前盲盒不一致");
    return;
  }

  const ok = publishMessage("/app/pay", {
    txId: resolvedTxId,
    payerId: payForm.payerId.trim(),
    payeeId: resolvedPayeeId,
    textContent: payForm.textContent.trim(),
    ransomFileName: payForm.ransomFileName,
    ransomFileContentBase64: payForm.ransomFileContentBase64,
  });

  if (!ok) {
    return;
  }

  pendingPay.value = {
    txId: resolvedTxId,
    fileName: payForm.ransomFileName,
    imageUrl: payForm.ransomPreviewUrl,
    textContent: payForm.textContent,
  };
  ElMessage.info("赎金文件已提交，等待仲裁确认");
}

function handleAck(message) {
  const payload = message.payload || {};
  if (message.type === "INITIATE_ACK") {
    payForm.txId = payload.txId || payForm.txId;
    if (pendingInitiate.value) {
      pushBubble({
        side: "right",
        type: "file",
        title: "你发起了一个加锁盲盒",
        fileName: pendingInitiate.value.fileName,
        imageUrl: pendingInitiate.value.imageUrl,
        text: [
          `目标 ${pendingInitiate.value.receiverId} · TTL ${pendingInitiate.value.ttlSeconds}s`,
          pendingInitiate.value.textContent,
        ]
          .filter(Boolean)
          .join("\n"),
      });
      pendingInitiate.value = null;
    }
    pushBubble({
      side: "center",
      type: "system",
      title: "担保已写入中间件",
      text: `交易号 ${payload.txId}，绝对过期时间 ${new Date(payload.expireAt).toLocaleTimeString()}`,
    });
    ElMessage.success("盲盒交易已被网关确认");
    return;
  }

  if (message.type === "READY_ACK") {
    ElMessage.success("离线补偿通道已就绪");
    return;
  }

  if (message.type === "PAY_ACK") {
    if (pendingPay.value) {
      pushBubble({
        side: "right",
        type: "file",
        title: "你提交了赎金文件",
        fileName: pendingPay.value.fileName,
        imageUrl: pendingPay.value.imageUrl,
        text: [`交易号 ${pendingPay.value.txId}`, pendingPay.value.textContent]
          .filter(Boolean)
          .join("\n"),
      });
      pendingPay.value = null;
    }
    pushBubble({
      side: "center",
      type: "system",
      title: payload.released ? "仲裁放行" : "仲裁拒绝",
      text: payload.released
        ? `交易 ${payload.txId} 已释放真实文件`
        : payload.reason,
    });
    ElMessage.success(
      payload.released ? "支付已确认，交易放行" : "支付已确认，但交易未放行",
    );
  }
}

function handleGatewayEvent(message) {
  const eventKey = [
    message.type,
    message.txId,
    message.fromUser,
    message.senderId,
  ]
    .filter(Boolean)
    .join("|");
  if (eventKey && message.type !== "BAIT_NOTIFICATION") {
    if (seenEventKeys.has(eventKey)) {
      return;
    }
    seenEventKeys.add(eventKey);
  }

  if (message.type === "BAIT_NOTIFICATION") {
    if (message.txId && seenBaitTxIds.has(message.txId)) {
      return;
    }
    if (message.txId) {
      seenBaitTxIds.add(message.txId);
    }

    payForm.txId = message.txId || "";
    payForm.payerId = session.userId.trim();
    payForm.payeeId = message.senderId || "";
    activeBait.txId = message.txId || "";
    activeBait.senderId = message.senderId || "";
    blindBox.visible = true;
    blindBox.txId = message.txId || "";
    blindBox.senderId = message.senderId || "";
    blindBox.fileName = "";
    startBlindBoxCountdown(message.expireAt);
    pushBubble({
      side: "left",
      type: "bait",
      title: `${message.senderId || "对方"} 发来一个上锁盲盒`,
      senderId: message.senderId || "",
      txId: message.txId || "",
      text: [`交易号 ${message.txId}`, message.textContent]
        .filter(Boolean)
        .join("\n"),
    });
    ElMessage.info("盲盒通知已到达");
    return;
  }

  if (message.type === "RANSOM_FILE") {
    pushBubble({
      side: "left",
      type: "file",
      title: `${message.fromUser || "对方"} 发送了赎金文件`,
      fileName: message.fileName,
      imageUrl: message.fileContentBase64,
      text: [`交易号 ${message.txId}`, message.textContent]
        .filter(Boolean)
        .join("\n"),
    });
    return;
  }

  if (message.type === "REAL_FILE_RELEASED") {
    clearBlindBox();
    pushBubble({
      side: "left",
      type: "file",
      title: "真实文件已释放到你的会话",
      fileName: message.fileName,
      imageUrl: message.fileContentBase64,
      text: [`来自 ${message.fromUser || "对方"}`, message.textContent]
        .filter(Boolean)
        .join("\n"),
    });
    ElMessage.success("真实文件已释放");
  }
}

watch(
  () => session.peerId,
  (peer) => {
    initiateForm.receiverId = (peer || "").trim();
  },
);

onBeforeUnmount(() => {
  clearBlindBox();
  if (stompClient.value?.active) {
    stompClient.value.deactivate();
  }
});
</script>

<template>
  <div class="page-shell">
    <div class="glow glow-a"></div>
    <div class="glow glow-b"></div>

    <section class="chat-layout">
      <aside class="sidebar">
        <div class="brand-block">
          <div class="brand-mark">HD</div>
          <div>
            <h1>Hostage-Drop</h1>
            <p>社交盲盒文件交换实验场</p>
          </div>
        </div>

        <el-card class="panel-card" shadow="never">
          <template #header>
            <div class="panel-title">身份接入</div>
          </template>
          <el-space direction="vertical" fill>
            <el-input
              v-model="session.userId"
              placeholder="当前用户ID，例如 B"
            />
            <el-input
              v-model="session.peerId"
              placeholder="对话对象ID，例如 A"
            />
            <el-button type="info" class="full-btn" plain @click="switchPeer"
              >切换对话对象</el-button
            >
            <el-button type="warning" class="full-btn" @click="connectGateway"
              >开始实时会话</el-button
            >
            <el-tag :type="onlineTagType" effect="dark">{{
              session.wsStatus
            }}</el-tag>
          </el-space>
        </el-card>

        <el-card class="panel-card persona-card" shadow="never">
          <template #header>
            <div class="panel-title">当前会话</div>
          </template>
          <div class="persona-pill">
            <span>你</span>
            <strong>{{ session.userId || "未命名" }}</strong>
          </div>
          <div class="persona-pill muted">
            <span>对话对象</span>
            <strong>{{ peerLabel }}</strong>
          </div>
          <p class="panel-tip">
            所有发起与支付动作都会实时送达，离线消息会自动补投。
          </p>
        </el-card>
      </aside>

      <section class="chat-panel">
        <header class="chat-header">
          <div>
            <h2>文件盲盒会话</h2>
            <p>左侧为对方消息，右侧为你的动作气泡</p>
          </div>
          <el-tag type="warning" effect="plain">实时担保通道</el-tag>
        </header>

        <main class="message-stream" ref="messageStream">
          <div v-if="!chatMessages.length" class="empty-state">
            <div class="empty-icon">💬</div>
            <p>先开始会话，再上传真实图片文件发起一次盲盒交易。</p>
          </div>

          <div
            v-for="item in chatMessages"
            :key="item.id"
            class="message-row"
            :class="`side-${item.side}`"
          >
            <div v-if="item.side !== 'center'" class="avatar">
              {{ item.side === "right" ? "我" : "TA" }}
            </div>

            <div
              class="bubble"
              :class="[`bubble-${item.side}`, `bubble-${item.type}`]"
            >
              <div class="bubble-title">{{ item.title }}</div>
              <div v-if="item.fileName" class="bubble-file">
                {{ item.fileName }}
              </div>
              <div v-if="item.text" class="bubble-text">{{ item.text }}</div>
              <div v-if="item.txStatus" class="bubble-status">
                状态：{{ item.txStatus }}
              </div>
              <img
                v-if="isImage(item.imageUrl)"
                :src="item.imageUrl"
                class="bubble-image"
                alt="preview"
              />
              <div class="bubble-time">{{ item.time }}</div>
            </div>
          </div>
        </main>

        <footer class="composer-zone">
          <el-tabs v-model="activeComposer" stretch>
            <el-tab-pane label="发起盲盒" name="initiate">
              <div class="composer-grid">
                <el-input
                  v-model="session.peerId"
                  placeholder="接收方ID，例如 user-c"
                />
                <el-input
                  v-model="initiateForm.textContent"
                  type="textarea"
                  :rows="3"
                  placeholder="盲盒留言（可选）"
                />
                <el-input-number
                  v-model="initiateForm.ttlSeconds"
                  :min="10"
                  :step="10"
                  class="wide-number"
                />
                <el-upload
                  class="uploader"
                  :auto-upload="false"
                  :show-file-list="true"
                  :limit="1"
                  accept="image/*"
                  :file-list="initiateFileList"
                  :on-change="onInitiateFileChange"
                  :on-remove="removeInitiateFile"
                >
                  <el-button type="primary" plain
                    >选择真实图片文件（选填）</el-button
                  >
                </el-upload>
              </div>
              <div
                v-if="isImage(initiateForm.filePreviewUrl)"
                class="preview-box"
              >
                <img
                  :src="initiateForm.filePreviewUrl"
                  alt="initiate-preview"
                />
              </div>
              <el-button type="warning" class="send-btn" @click="initiateTrade"
                >发起担保交易</el-button
              >
            </el-tab-pane>

            <el-tab-pane label="支付赎金" name="pay">
              <div class="composer-grid">
                <el-input
                  v-model="payForm.txId"
                  placeholder="交易ID（来自盲盒通知）"
                />
                <el-input
                  v-model="payForm.payeeId"
                  placeholder="收款方ID（自动绑定）"
                  readonly
                />
                <el-input
                  v-model="payForm.textContent"
                  type="textarea"
                  :rows="3"
                  placeholder="赎金留言（可选）"
                />
                <el-upload
                  class="uploader"
                  :auto-upload="false"
                  :show-file-list="true"
                  :limit="1"
                  accept="image/*"
                  :file-list="payFileList"
                  :on-change="onPayFileChange"
                  :on-remove="removePayFile"
                >
                  <el-button type="danger" plain>选择赎金图片文件</el-button>
                </el-upload>
              </div>
              <div
                v-if="isImage(payForm.ransomPreviewUrl)"
                class="preview-box preview-ransom"
              >
                <img :src="payForm.ransomPreviewUrl" alt="pay-preview" />
              </div>
              <el-button type="danger" class="send-btn" @click="payRansom"
                >支付赎金并请求释放</el-button
              >
            </el-tab-pane>
          </el-tabs>
        </footer>
      </section>
    </section>

    <el-dialog
      v-model="blindBox.visible"
      width="420px"
      align-center
      :show-close="true"
      :close-on-click-modal="true"
      @close="dismissBlindBox"
      destroy-on-close
      class="blindbox-dialog"
    >
      <div class="blindbox-body">
        <div class="locked-file-icon">
          <span class="paper">FILE</span>
          <span class="lock">🔒</span>
        </div>
        <h3>收到文件盲盒</h3>
        <p>{{ blindBox.senderId || "对方" }} 向你投递了上锁盲盒</p>
        <div class="countdown-ring">{{ blindBox.countdownLabel }}</div>
        <div class="dialog-tip">
          倒计时结束后，前端弹窗会自动关闭，超时支付也会被底层 TTL 否决。
        </div>
        <el-button type="warning" plain @click="dismissBlindBox"
          >我知道了，去交易</el-button
        >
      </div>
    </el-dialog>
  </div>
</template>
