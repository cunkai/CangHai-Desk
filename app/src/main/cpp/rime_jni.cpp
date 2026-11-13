/*
 * librime 的 JNI 外壳。
 *
 * librime 是 C++ 写的，对外只暴露 C 接口 rime_api.h（函数表靠 rime_get_api() 取）。
 * 这里做四件事：初始化一次、建会话、喂按键、把候选拼成 JSON 回去。
 * 上屏文本也在同一份 JSON 里（commit），Java 侧直接拿去插 DOM。
 *
 * 编译见 build_rime_so.bat：NDK clang 直接链 librime.a + 五个依赖静态库。
 */
#include <jni.h>
#include <stdio.h>
#include <string>

#include "rime_api.h"

namespace {

RimeApi *api() {
  static RimeApi *a = rime_get_api();
  return a;
}

bool g_inited = false;

std::string js(JNIEnv *env, jstring s) {
  if (!s) return std::string();
  const char *c = env->GetStringUTFChars(s, nullptr);
  std::string r = c ? c : "";
  if (c) env->ReleaseStringUTFChars(s, c);
  return r;
}

const char *cs(const std::string &s) { return s.c_str(); }

std::string esc(const char *s) {
  if (!s) return std::string();
  std::string out;
  out.reserve(32);
  for (const char *p = s; *p; ++p) {
    unsigned char c = (unsigned char) *p;
    switch (c) {
      case '"': out += "\\\""; break;
      case '\\': out += "\\\\"; break;
      case '\n': out += "\\n"; break;
      case '\r': out += "\\r"; break;
      case '\t': out += "\\t"; break;
      default:
        if (c < 0x20) {
          char b[8];
          snprintf(b, sizeof(b), "\\u%04x", c);
          out += b;
        } else {
          out += (char) c;
        }
    }
  }
  return out;
}

}  // namespace

extern "C" {

JNIEXPORT jstring JNICALL
Java_com_aosen_shizukudex_RimeBridge_nativeVersion(JNIEnv *env, jclass clazz) {
  (void) clazz;
  RimeApi *a = api();
  const char *v = (a && a->get_version) ? a->get_version() : nullptr;
  return env->NewStringUTF(v ? v : "unavailable");
}

/*
 * 初始化：sharedDir 是只读方案数据（从 assets 解出来的），userDir 是用户词典，
 * logDir 放日志。第一次会跑一次部署（编译词典），慢几秒到几十秒，由 Java 侧丢到子线程。
 */
JNIEXPORT jboolean JNICALL
Java_com_aosen_shizukudex_RimeBridge_nativeInit(JNIEnv *env, jclass clazz,
                                                jstring sharedDir, jstring userDir,
                                                jstring logDir) {
  (void) clazz;
  if (g_inited) return JNI_TRUE;
  RimeApi *a = api();
  if (!a) return JNI_FALSE;

  std::string shared = js(env, sharedDir);
  std::string user = js(env, userDir);
  std::string logs = js(env, logDir);

  RIME_STRUCT(RimeTraits, traits);
  traits.shared_data_dir = cs(shared);
  traits.user_data_dir = cs(user);
  traits.log_dir = cs(logs);
  traits.distribution_name = "Canghai";
  traits.distribution_code_name = "canghai";
  traits.distribution_version = "1.0";
  traits.app_name = "rime.canghai";
  traits.min_log_level = 1;

  a->setup(&traits);
  a->initialize(&traits);

  /* 首次启动要生成 *.bin 词典；已经有就不用重编（full_check=false）。 */
  if (a->start_maintenance) {
    a->start_maintenance(False);
    if (a->join_maintenance_thread) a->join_maintenance_thread();
  }
  g_inited = true;
  return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_com_aosen_shizukudex_RimeBridge_nativeReady(JNIEnv *env, jclass clazz) {
  (void) env;
  (void) clazz;
  return g_inited ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jlong JNICALL
Java_com_aosen_shizukudex_RimeBridge_nativeCreateSession(JNIEnv *env, jclass clazz) {
  (void) env;
  (void) clazz;
  RimeApi *a = api();
  if (!a || !g_inited) return 0;
  return (jlong) a->create_session();
}

JNIEXPORT void JNICALL
Java_com_aosen_shizukudex_RimeBridge_nativeDestroySession(JNIEnv *env, jclass clazz,
                                                          jlong sid) {
  (void) env;
  (void) clazz;
  RimeApi *a = api();
  if (a && sid) a->destroy_session((RimeSessionId) sid);
}

/*
 * 处理一个按键，返回 JSON：
 *   {"ok":true,"commit":"你","preedit":"ni","sel":0,"cands":[{"t":"你","c":"ni"},...]}
 * keycode 用 ASCII（'n'=110），Enter=0xff0d，BackSpace=0xff08，Escape=0xff1b，空格=32。
 */
JNIEXPORT jstring JNICALL
Java_com_aosen_shizukudex_RimeBridge_nativeProcessKey(JNIEnv *env, jclass clazz,
                                                      jlong sid, jint keycode, jint mask) {
  (void) clazz;
  RimeApi *a = api();
  if (!a || !sid) return env->NewStringUTF("{\"ok\":false}");

  a->process_key((RimeSessionId) sid, (int) keycode, (int) mask);

  std::string commitText;
  RIME_STRUCT(RimeCommit, commit);
  if (a->get_commit((RimeSessionId) sid, &commit)) {
    if (commit.text) commitText = commit.text;
    a->free_commit(&commit);
  }

  std::string preedit;
  std::string cands;
  int sel = -1;
  if (a->get_context) {
    RIME_STRUCT(RimeContext, ctx);
    if (a->get_context((RimeSessionId) sid, &ctx)) {
      if (ctx.composition.preedit) preedit = ctx.composition.preedit;
      sel = ctx.menu.highlighted_candidate_index;
      for (int i = 0; i < ctx.menu.num_candidates; ++i) {
        if (i) cands += ",";
        cands += "{\"t\":\"";
        cands += esc(ctx.menu.candidates[i].text);
        cands += "\",\"c\":\"";
        cands += esc(ctx.menu.candidates[i].comment);
        cands += "\"}";
      }
      a->free_context(&ctx);
    }
  }

  std::string out = "{\"ok\":true,\"commit\":\"";
  out += esc(commitText.c_str());
  out += "\",\"preedit\":\"";
  out += esc(preedit.c_str());
  out += "\",\"sel\":";
  out += std::to_string(sel);
  out += ",\"cands\":[";
  out += cands;
  out += "]}";
  return env->NewStringUTF(out.c_str());
}

JNIEXPORT jstring JNICALL
Java_com_aosen_shizukudex_RimeBridge_nativeSelect(JNIEnv *env, jclass clazz,
                                                  jlong sid, jint index) {
  (void) clazz;
  RimeApi *a = api();
  if (!a || !sid) return env->NewStringUTF("{\"ok\":false}");

  a->select_candidate((RimeSessionId) sid, (size_t) index);

  std::string commitText;
  RIME_STRUCT(RimeCommit, commit);
  if (a->get_commit((RimeSessionId) sid, &commit)) {
    if (commit.text) commitText = commit.text;
    a->free_commit(&commit);
  }
  std::string out = "{\"ok\":true,\"commit\":\"";
  out += esc(commitText.c_str());
  out += "\"}";
  return env->NewStringUTF(out.c_str());
}

JNIEXPORT void JNICALL
Java_com_aosen_shizukudex_RimeBridge_nativeReset(JNIEnv *env, jclass clazz, jlong sid) {
  (void) env;
  (void) clazz;
  RimeApi *a = api();
  if (a && sid) a->clear_composition((RimeSessionId) sid);
}

}  // extern "C"
