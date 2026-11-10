#include <jni.h>
#include <errno.h>
#include <fcntl.h>
#include <signal.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <termios.h>
#include <unistd.h>
#include <pthread.h>

#define MAX_SESSIONS 32
typedef struct { int fd; pid_t pid; } session_entry;
static session_entry sessions[MAX_SESSIONS];
static pthread_mutex_t sessions_lock = PTHREAD_MUTEX_INITIALIZER;

static void remember_session(int fd, pid_t pid) {
    pthread_mutex_lock(&sessions_lock);
    for (int i=0;i<MAX_SESSIONS;i++) if (sessions[i].fd == 0) { sessions[i].fd=fd; sessions[i].pid=pid; break; }
    pthread_mutex_unlock(&sessions_lock);
}
static pid_t forget_session(int fd) {
    pid_t pid=-1;
    pthread_mutex_lock(&sessions_lock);
    for (int i=0;i<MAX_SESSIONS;i++) if (sessions[i].fd == fd) { pid=sessions[i].pid; sessions[i].fd=0; sessions[i].pid=-1; break; }
    pthread_mutex_unlock(&sessions_lock);
    return pid;
}

JNIEXPORT jint JNICALL
Java_com_phoneai_app_NativePty_spawn(JNIEnv *env, jobject thiz, jobjectArray argv, jobjectArray envp,
                                     jint rows, jint cols, jintArray pid_out) {
    if (!argv || (*env)->GetArrayLength(env, argv) < 1 || !pid_out || (*env)->GetArrayLength(env, pid_out) < 1) return -1;
    jsize argc=(*env)->GetArrayLength(env, argv), envc=envp?(*env)->GetArrayLength(env,envp):0;
    char **args=calloc((size_t)argc+1,sizeof(char*));
    char **vars=calloc((size_t)envc+1,sizeof(char*));
    if (!args || !vars) { free(args); free(vars); return -1; }
    for (jsize i=0;i<argc;i++) { jstring s=(jstring)(*env)->GetObjectArrayElement(env,argv,i); const char *p=(*env)->GetStringUTFChars(env,s,NULL); args[i]=strdup(p); (*env)->ReleaseStringUTFChars(env,s,p); (*env)->DeleteLocalRef(env,s); }
    for (jsize i=0;i<envc;i++) { jstring s=(jstring)(*env)->GetObjectArrayElement(env,envp,i); const char *p=(*env)->GetStringUTFChars(env,s,NULL); vars[i]=strdup(p); (*env)->ReleaseStringUTFChars(env,s,p); (*env)->DeleteLocalRef(env,s); }
    int master=posix_openpt(O_RDWR|O_NOCTTY|O_CLOEXEC);
    if (master<0 || grantpt(master)!=0 || unlockpt(master)!=0) goto fail;
    char *slave_name=ptsname(master);
    if (!slave_name) { close(master); master=-1; goto fail; }
    pid_t pid=fork();
    if (pid<0) { close(master); master=-1; goto fail; }
    if (pid==0) {
        close(master);
        setsid();
        int slave=open(slave_name,O_RDWR);
        if (slave<0) _exit(126);
        ioctl(slave,TIOCSCTTY,0);
        struct winsize ws={.ws_row=(unsigned short)(rows>0?rows:24),.ws_col=(unsigned short)(cols>0?cols:80)};
        ioctl(slave,TIOCSWINSZ,&ws);
        dup2(slave,STDIN_FILENO); dup2(slave,STDOUT_FILENO); dup2(slave,STDERR_FILENO);
        if (slave>STDERR_FILENO) close(slave);
        execve(args[0],args,vars);
        _exit(errno==ENOENT?127:126);
    }
    remember_session(master,pid);
    (*env)->SetIntArrayRegion(env,pid_out,0,1,(jint*)&pid);
    for (jsize i=0;i<argc;i++) free(args[i]); for (jsize i=0;i<envc;i++) free(vars[i]); free(args); free(vars);
    return master;
fail:
    for (jsize i=0;i<argc;i++) free(args[i]); for (jsize i=0;i<envc;i++) free(vars[i]); free(args); free(vars);
    return -1;
}

JNIEXPORT jint JNICALL
Java_com_phoneai_app_NativePty_read(JNIEnv *env,jobject thiz,jint fd,jbyteArray target,jint length) {
    if(fd<0||!target||length<=0)return -1;
    jsize cap=(*env)->GetArrayLength(env,target); if(length>cap)length=cap;
    jbyte *bytes=(*env)->GetByteArrayElements(env,target,NULL); if(!bytes)return -1;
    ssize_t n=read(fd,bytes,(size_t)length);
    if(n>0)(*env)->ReleaseByteArrayElements(env,target,bytes,0); else (*env)->ReleaseByteArrayElements(env,target,bytes,JNI_ABORT);
    return (jint)n;
}
JNIEXPORT jint JNICALL
Java_com_phoneai_app_NativePty_write(JNIEnv *env,jobject thiz,jint fd,jbyteArray source,jint length) {
    if(fd<0||!source||length<=0)return -1;
    jsize cap=(*env)->GetArrayLength(env,source); if(length>cap)length=cap;
    jbyte *bytes=(*env)->GetByteArrayElements(env,source,NULL); if(!bytes)return -1;
    ssize_t n=write(fd,bytes,(size_t)length);
    (*env)->ReleaseByteArrayElements(env,source,bytes,JNI_ABORT);
    return (jint)n;
}
JNIEXPORT void JNICALL
Java_com_phoneai_app_NativePty_resize(JNIEnv *env,jobject thiz,jint fd,jint rows,jint cols) {
    if(fd<0)return;
    struct winsize ws={.ws_row=(unsigned short)(rows>0?rows:24),.ws_col=(unsigned short)(cols>0?cols:80)};
    ioctl(fd,TIOCSWINSZ,&ws);
}
JNIEXPORT jint JNICALL
Java_com_phoneai_app_NativePty_close(JNIEnv *env,jobject thiz,jint fd) {
    if(fd<0)return -1;
    pid_t pid=forget_session(fd);
    if(pid>0) kill(-pid,SIGHUP);
    int rc=close(fd);
    if(pid>0) { int status=0; if(waitpid(pid,&status,WNOHANG)==0) { kill(pid,SIGTERM); usleep(100000); if(waitpid(pid,&status,WNOHANG)==0) kill(pid,SIGKILL); } }
    return rc;
}
