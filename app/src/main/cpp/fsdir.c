/* Fast folder listing: names, sizes, dates and types in one call (readdir + fstatat), instead of one
   Java attribute call per entry. Shared storage is a FUSE file system: every fstatat is a round trip to
   the system's storage daemon, which serves several requests at once, so the attributes are read by up
   to 4 threads in parallel. */

#include <pthread.h>

#include <dirent.h>
#include <fcntl.h>
#include <sys/stat.h>
#include <unistd.h>

#include "jutil.h"

typedef struct
{
  int dfd;
  char **names;
  jlong *info;
  size_t from, to;
} StatJob;

static void stat_one(int dfd, const char *nm, jlong *in)
{
  struct stat st;
  if (fstatat(dfd, nm, &st, 0) == 0)   /* follows links, like the Java version */
  {
    in[0] = (jlong)st.st_size;
    in[1] = (jlong)st.st_mtim.tv_sec * 1000 + st.st_mtim.tv_nsec / 1000000;
    in[2] = (S_ISDIR(st.st_mode) ? 1 : 0) | 2;
  }
}

static void *stat_range(void *arg)
{
  StatJob *j = (StatJob *)arg;
  for (size_t i = j->from; i < j->to; i++)
    stat_one(j->dfd, j->names[i], j->info + i * 3);
  return NULL;
}

/* Entries of [path]: Object[] { String[] names, long[] info } with 3 longs per entry
   (size, modified ms, flags: 1 = folder, 2 = attributes read). Without [withStat] only the type from
   the folder itself is given (fast: no access per entry; links and unknown types are still read).
   null if the folder cannot be read. */
JNIEXPORT jobjectArray JNICALL
Java_de_regepower_dualfiles_NativeLib_listDir(JNIEnv *env, jclass cls, jstring jPath, jboolean withStat)
{
  (void)cls;
  char *path = jstr_utf8(env, jPath);
  if (!path)
    return NULL;
  int dfd = open(path, O_RDONLY | O_DIRECTORY | O_CLOEXEC);
  free(path);
  if (dfd < 0)
    return NULL;
  DIR *d = fdopendir(dfd);
  if (!d)
  {
    close(dfd);
    return NULL;
  }

  size_t cap = 256, n = 0;
  char **names = (char **)malloc(cap * sizeof(char *));
  jlong *info = (jlong *)malloc(cap * 3 * sizeof(jlong));
  int ok = names && info;
  struct dirent *e;
  while (ok && (e = readdir(d)) != NULL)
  {
    const char *nm = e->d_name;
    if (nm[0] == '.' && (nm[1] == 0 || (nm[1] == '.' && nm[2] == 0)))
      continue;
    if (n == cap)
    {
      cap *= 2;
      char **nn = (char **)realloc(names, cap * sizeof(char *));
      jlong *ni = nn ? (jlong *)realloc(info, cap * 3 * sizeof(jlong)) : NULL;
      if (nn)
        names = nn;
      if (ni)
        info = ni;
      if (!nn || !ni)
      {
        ok = 0;
        break;
      }
    }
    jlong *in = info + n * 3;
    in[0] = 0;
    in[1] = 0;
    in[2] = e->d_type == DT_DIR ? 1 : 0;
    if (!withStat && (e->d_type == DT_UNKNOWN || e->d_type == DT_LNK))
      stat_one(dfd, nm, in);
    names[n] = strdup(nm);
    if (!names[n])
    {
      ok = 0;
      break;
    }
    n++;
  }
  if (ok && withStat)
  {
    /* Attributes in parallel: up to 4 threads for big folders, this thread does the first part */
    size_t parts = n < 64 ? 1 : n < 256 ? 2 : 4;
    StatJob jobs[4];
    pthread_t th[4];
    int started[4] = {0, 0, 0, 0};
    for (size_t k = 0; k < parts; k++)
    {
      jobs[k].dfd = dfd;
      jobs[k].names = names;
      jobs[k].info = info;
      jobs[k].from = n * k / parts;
      jobs[k].to = n * (k + 1) / parts;
      if (k > 0)
        started[k] = pthread_create(&th[k], NULL, stat_range, &jobs[k]) == 0;
    }
    stat_range(&jobs[0]);
    for (size_t k = 1; k < parts; k++)
    {
      if (started[k])
        pthread_join(th[k], NULL);
      else
        stat_range(&jobs[k]);
    }
  }
  closedir(d);   /* also closes dfd */

  jobjectArray result = NULL;
  if (ok)
  {
    jclass strClass = (*env)->FindClass(env, "java/lang/String");
    jclass objClass = (*env)->FindClass(env, "java/lang/Object");
    jobjectArray jNames = (*env)->NewObjectArray(env, (jsize)n, strClass, NULL);
    jlongArray jInfo = (*env)->NewLongArray(env, (jsize)(n * 3));
    if (jNames && jInfo)
    {
      for (size_t i = 0; i < n; i++)
      {
        jstring s = utf8_jstr(env, names[i], strlen(names[i]));
        if (!s)
          break;
        (*env)->SetObjectArrayElement(env, jNames, (jsize)i, s);
        (*env)->DeleteLocalRef(env, s);
      }
      (*env)->SetLongArrayRegion(env, jInfo, 0, (jsize)(n * 3), info);
      result = (*env)->NewObjectArray(env, 2, objClass, NULL);
      if (result)
      {
        (*env)->SetObjectArrayElement(env, result, 0, jNames);
        (*env)->SetObjectArrayElement(env, result, 1, jInfo);
      }
    }
  }
  for (size_t i = 0; i < n; i++)
    free(names[i]);
  free(names);
  free(info);
  return result;
}

/* ---- Copying a file in the kernel ---- */

#include <errno.h>
#include <sys/sendfile.h>
#include <sys/syscall.h>

#define STEP (4L * 1024 * 1024)

/* Copies [src] to [dst] (created or truncated) without passing the bytes through Java: copy_file_range
   (in the kernel, also inside the storage daemon where it supports it), else sendfile, else read/write
   with a 1 MB buffer. Progress every 4 MB through ArcProgress.step(done, total); false cancels.
   0 = done, -1 = error, -2 = cancelled (the half-copied file is removed). */
JNIEXPORT jint JNICALL
Java_de_regepower_dualfiles_NativeLib_copyFile(JNIEnv *env, jclass cls, jstring jSrc, jstring jDst, jobject progress)
{
  (void)cls;
  char *src = jstr_utf8(env, jSrc);
  char *dst = jstr_utf8(env, jDst);
  int result = -1, in = -1, out = -1;
  char *buf = NULL;
  if (!src || !dst)
    goto done;
  in = open(src, O_RDONLY | O_CLOEXEC);
  if (in < 0)
    goto done;
  struct stat st;
  if (fstat(in, &st) != 0 || S_ISDIR(st.st_mode))
    goto done;
  out = open(dst, O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC, 0666);
  if (out < 0)
    goto done;
  jclass pc = (*env)->GetObjectClass(env, progress);
  jmethodID step = (*env)->GetMethodID(env, pc, "step", "(JJ)Z");
  if (!step)
    goto done;
  jlong total = (jlong)st.st_size, copied = 0;
  int mode = 0; /* 0 copy_file_range, 1 sendfile, 2 read/write */
  for (;;)
  {
    size_t want = STEP;
    ssize_t n;
    if (mode == 0)
    {
      n = syscall(__NR_copy_file_range, in, NULL, out, NULL, want, 0);
      if (n < 0 && errno != EINTR)
      {
        mode = 1; /* not supported here (other file system, old kernel, storage daemon): next way */
        continue;
      }
    }
    else if (mode == 1)
    {
      n = sendfile(out, in, NULL, want);
      if (n < 0 && errno != EINTR)
      {
        mode = 2;
        continue;
      }
    }
    else
    {
      if (!buf && !(buf = (char *)malloc(1024 * 1024)))
        goto done;
      n = read(in, buf, 1024 * 1024);
      if (n > 0)
      {
        ssize_t w = 0;
        while (w < n)
        {
          ssize_t k = write(out, buf + w, (size_t)(n - w));
          if (k < 0)
          {
            if (errno == EINTR)
              continue;
            goto done;
          }
          w += k;
        }
      }
      else if (n < 0 && errno != EINTR)
        goto done;
    }
    if (n < 0)
      continue; /* EINTR */
    if (n == 0 && mode < 2 && copied < total)
    {
      mode++; /* some file systems answer 0 instead of an error: try the next way */
      continue;
    }
    if (n == 0)
      break; /* end of the file */
    copied += n;
    if (!(*env)->CallBooleanMethod(env, progress, step, copied, total) || (*env)->ExceptionCheck(env))
    {
      result = -2;
      goto done;
    }
  }
  result = 0;
done:
  free(buf);
  if (in >= 0)
    close(in);
  if (out >= 0)
    close(out);
  if (result == -2 && dst)
    unlink(dst);
  free(src);
  free(dst);
  return result;
}

/* ---- Size of a folder tree ---- */

static void walk(int dfd, const char *name, jlong *bytes, jlong *count)
{
  struct stat st;
  if (fstatat(dfd, name, &st, AT_SYMLINK_NOFOLLOW) != 0)
    return;
  (*count)++;
  if (!S_ISDIR(st.st_mode))
  {
    *bytes += (jlong)st.st_size;
    return;
  }
  int fd = openat(dfd, name, O_RDONLY | O_DIRECTORY | O_CLOEXEC);
  if (fd < 0)
    return;
  DIR *d = fdopendir(fd);
  if (!d)
  {
    close(fd);
    return;
  }
  struct dirent *e;
  while ((e = readdir(d)) != NULL)
  {
    const char *nm = e->d_name;
    if (nm[0] == '.' && (nm[1] == 0 || (nm[1] == '.' && nm[2] == 0)))
      continue;
    walk(fd, nm, bytes, count);
  }
  closedir(d);
}

typedef struct
{
  int dfd;
  char **names;
  size_t n;
  size_t next;   /* taken with __atomic_fetch_add */
  jlong bytes;   /* added with __atomic_fetch_add */
  jlong count;
} TreeJob;

static void *tree_worker(void *arg)
{
  TreeJob *j = (TreeJob *)arg;
  jlong bytes = 0, count = 0;
  for (;;)
  {
    size_t i = __atomic_fetch_add(&j->next, 1, __ATOMIC_RELAXED);
    if (i >= j->n)
      break;
    walk(j->dfd, j->names[i], &bytes, &count);
  }
  __atomic_fetch_add(&j->bytes, bytes, __ATOMIC_RELAXED);
  __atomic_fetch_add(&j->count, count, __ATOMIC_RELAXED);
  return NULL;
}

/* { bytes of all files, entries including [path] itself } of a file or folder tree; links are not
   followed. The top-level entries are shared out to up to 4 threads (shared storage answers several
   requests at once). null if [path] cannot be read. */
JNIEXPORT jlongArray JNICALL
Java_de_regepower_dualfiles_NativeLib_treeSize(JNIEnv *env, jclass cls, jstring jPath)
{
  (void)cls;
  char *path = jstr_utf8(env, jPath);
  if (!path)
    return NULL;
  struct stat st;
  jlong res[2] = {0, 0};
  if (lstat(path, &st) != 0)
  {
    free(path);
    return NULL;
  }
  res[1] = 1;
  if (!S_ISDIR(st.st_mode))
    res[0] = (jlong)st.st_size;
  else
  {
    int dfd = open(path, O_RDONLY | O_DIRECTORY | O_CLOEXEC);
    DIR *d = dfd >= 0 ? fdopendir(dfd) : NULL;
    if (!d)
    {
      if (dfd >= 0)
        close(dfd);
      free(path);
      return NULL;
    }
    size_t cap = 64;
    TreeJob job = {dfd, (char **)malloc(cap * sizeof(char *)), 0, 0, 0, 0};
    struct dirent *e;
    while (job.names && (e = readdir(d)) != NULL)
    {
      const char *nm = e->d_name;
      if (nm[0] == '.' && (nm[1] == 0 || (nm[1] == '.' && nm[2] == 0)))
        continue;
      if (job.n == cap)
      {
        char **nn = (char **)realloc(job.names, (cap *= 2) * sizeof(char *));
        if (!nn)
          break;
        job.names = nn;
      }
      job.names[job.n] = strdup(nm);
      if (job.names[job.n])
        job.n++;
    }
    if (job.names)
    {
      pthread_t th[3];
      int started[3] = {0, 0, 0};
      size_t extra = job.n < 8 ? 0 : 3;
      for (size_t k = 0; k < extra; k++)
        started[k] = pthread_create(&th[k], NULL, tree_worker, &job) == 0;
      tree_worker(&job);
      for (size_t k = 0; k < extra; k++)
        if (started[k])
          pthread_join(th[k], NULL);
      for (size_t i = 0; i < job.n; i++)
        free(job.names[i]);
      free(job.names);
    }
    closedir(d);
    res[0] = job.bytes;
    res[1] += job.count;
  }
  free(path);
  jlongArray out = (*env)->NewLongArray(env, 2);
  if (out)
    (*env)->SetLongArrayRegion(env, out, 0, 2, res);
  return out;
}
