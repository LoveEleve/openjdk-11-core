/*
 * Copyright (c) 1995, 2018, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */


/*
 * This file contains the main entry point into the launcher code
 * this is the only file which will be repeatedly compiled by other
 * tools. The rest of the files will be linked in.
 */

#include "defines.h"
#include "jli_util.h"
#include "jni.h"

#ifdef _MSC_VER
#if _MSC_VER > 1400 && _MSC_VER < 1600

/*
 * When building for Microsoft Windows, main has a dependency on msvcr??.dll.
 *
 * When using Visual Studio 2005 or 2008, that must be recorded in
 * the [java,javaw].exe.manifest file.
 *
 * As of VS2010 (ver=1600), the runtimes again no longer need manifests.
 *
 * Reference:
 *     C:/Program Files/Microsoft SDKs/Windows/v6.1/include/crtdefs.h
 */
#include <crtassem.h>
#ifdef _M_IX86

#pragma comment(linker,"/manifestdependency:\"type='win32' "            \
        "name='" __LIBRARIES_ASSEMBLY_NAME_PREFIX ".CRT' "              \
        "version='" _CRT_ASSEMBLY_VERSION "' "                          \
        "processorArchitecture='x86' "                                  \
        "publicKeyToken='" _VC_ASSEMBLY_PUBLICKEYTOKEN "'\"")

#endif /* _M_IX86 */

//This may not be necessary yet for the Windows 64-bit build, but it
//will be when that build environment is updated.  Need to test to see
//if it is harmless:
#ifdef _M_AMD64

#pragma comment(linker,"/manifestdependency:\"type='win32' "            \
        "name='" __LIBRARIES_ASSEMBLY_NAME_PREFIX ".CRT' "              \
        "version='" _CRT_ASSEMBLY_VERSION "' "                          \
        "processorArchitecture='amd64' "                                \
        "publicKeyToken='" _VC_ASSEMBLY_PUBLICKEYTOKEN "'\"")

#endif  /* _M_AMD64 */
#endif  /* _MSC_VER > 1400 && _MSC_VER < 1600 */
#endif  /* _MSC_VER */

/*
 * Entry point.
 */
#ifdef JAVAW

char **__initenv;

int WINAPI
WinMain(HINSTANCE inst, HINSTANCE previnst, LPSTR cmdline, int cmdshow)
{
    int margc;
    char** margv;
    int jargc;
    char** jargv;
    const jboolean const_javaw = JNI_TRUE;

    __initenv = _environ;
#else /* JAVAW */
/*
    这个是 main()的传参:
    以 java -cp /data/workspace/demo/src com.wjcoder.Main -Xint 为例
        - argc:参数的个数 - 5
        - argv:参数数组 
            {
                0 - java
                1 - -cp
                2 - /data/workspace/demo/src
                3 - com.wjcoder.Main
                4 - -Xint
            }    
*/
JNIEXPORT int
main(int argc, char **argv) {
    int margc; // 主参数个数
    char **margv; // 主参数数组
    int jargc; // Java虚拟机参数个数
    char **jargv; // Java虚拟机参数数组
    const jboolean const_javaw = JNI_FALSE; // linux下默认为false
#endif /* JAVAW */
    {
        int i, main_jargc, extra_jargc;
        JLI_List list;
        /*
            const_jargs:编译时定义的java参数(java命令通常为空)
            const_extra_jargs:额外的Java参数
            对于Java-Launcher来说,这里可以跳过
        */
        main_jargc = (sizeof(const_jargs) / sizeof(char *)) > 1
                     ? sizeof(const_jargs) / sizeof(char *)
                     : 0; // ignore the null terminator index

        extra_jargc = (sizeof(const_extra_jargs) / sizeof(char *)) > 1
                      ? sizeof(const_extra_jargs) / sizeof(char *)
                      : 0; // ignore the null terminator index
        // skip
        if (main_jargc > 0 && extra_jargc > 0) { // combine extra java args
            jargc = main_jargc + extra_jargc;
            list = JLI_List_new(jargc + 1);

            for (i = 0; i < extra_jargc; i++) {
                JLI_List_add(list, JLI_StringDup(const_extra_jargs[i]));
            }

            for (i = 0; i < main_jargc; i++) {
                JLI_List_add(list, JLI_StringDup(const_jargs[i]));
            }

            // terminate the list
            JLI_List_add(list, NULL);
            jargv = list->elements;
        } else if (extra_jargc > 0) { // should never happen
            fprintf(stderr, "EXTRA_JAVA_ARGS defined without JAVA_ARGS");
            abort();
        } else { // no extra args, business as usual
            jargc = main_jargc;
            jargv = (char **) const_jargs;
        }
    }
    JLI_InitArgProcessing(jargc > 0, const_disable_argfile);

#ifdef _WIN32
    {
        int i = 0;
        if (getenv(JLDEBUG_ENV_ENTRY) != NULL) {
            printf("Windows original main args:\n");
            for (i = 0 ; i < __argc ; i++) {
                printf("wwwd_args[%d] = %s\n", i, __argv[i]);
            }
        }
    }
    JLI_CmdToArgs(GetCommandLine());
    margc = JLI_GetStdArgc();
    // add one more to mark the end
    margv = (char **)JLI_MemAlloc((margc + 1) * (sizeof(char *)));
    {
        int i = 0;
        StdArg *stdargs = JLI_GetStdArgs();
        for (i = 0 ; i < margc ; i++) {
            margv[i] = stdargs[i].arg;
        }
        margv[i] = NULL;
    }
#else /* *NIXES */
    {
        // accommodate the NULL at the end
        // 构建参数列表
        JLI_List args = JLI_List_new(argc + 1);
        int i = 0;
        // Add first arg, which is the app name
        // 添加程序名称
        JLI_List_add(args, JLI_StringDup(argv[0]));
        // Append JDK_JAVA_OPTIONS
        // 处理 JDK_JAVA_OPTIONS 环境变量
        if (JLI_AddArgsFromEnvVar(args, JDK_JAVA_OPTIONS)) {
            // JLI_SetTraceLauncher is not called yet
            // Show _JAVA_OPTIONS content along with JDK_JAVA_OPTIONS to aid diagnosis
            if (getenv(JLDEBUG_ENV_ENTRY)) {
                char *tmp = getenv("_JAVA_OPTIONS");
                if (NULL != tmp) {
                    JLI_ReportMessage(ARG_INFO_ENVVAR, "_JAVA_OPTIONS", tmp);
                }
            }
        }
        // 遍历剩余命令行参数
        // Iterate the rest of command line
        for (i = 1; i < argc; i++) {
            JLI_List argsInFile = JLI_PreprocessArg(argv[i], JNI_TRUE);
            if (NULL == argsInFile) {
                JLI_List_add(args, JLI_StringDup(argv[i]));
            } else {
                int cnt, idx;
                cnt = argsInFile->size;
                for (idx = 0; idx < cnt; idx++) {
                    JLI_List_add(args, argsInFile->elements[idx]);
                }
                // Shallow free, we reuse the string to avoid copy
                JLI_MemFree(argsInFile->elements);
                JLI_MemFree(argsInFile);
            }
        }
        margc = args->size;
        // add the NULL pointer at argv[argc]
        JLI_List_add(args, NULL);
        margv = args->elements;
    }
#endif /* WIN32 */
    /*
        java -Xint -cp /data/workspace/demo/src com.wjcoder.Main 为例
        margc : 5
        margv: {
            0 - java
            1 - -cp
            2 - /data/workspace/demo/src
            3 - com.wjcoder.Main
            4 - -Xint
        }
    */
    return JLI_Launch(margc,  margv,  // 主参数(包含程序名,环境变量,用户参数)
                      jargc,(const char **) jargv, // Java虚拟机参数（编译时定义）
                      0, NULL, // 应用类路径参数（这里为空）
                      VERSION_STRING, // JDK版本信息
                      DOT_VERSION,  // 点分版本号
                      (const_progname != NULL) ? const_progname : *margv, // 程序名称 java
                      (const_launcher != NULL) ? const_launcher : *margv, // 启动器名称 openjdk
                      jargc > 0, // 是否有预定义Java参数
                      const_cpwildcard, // 是否支持类路径通配符
                      const_javaw, // Linux下不是javaw
                      0); // 保留参数
}
