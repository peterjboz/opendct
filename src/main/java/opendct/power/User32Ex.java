/*
 * Copyright 2015 The OpenDCT Authors. All Rights Reserved
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package opendct.power;

import com.sun.jna.Native;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.win32.W32APIOptions;

public interface User32Ex extends User32 {
    public static final User32Ex MYINSTANCE = (User32Ex) Native.loadLibrary("user32", User32Ex.class, W32APIOptions.UNICODE_OPTIONS);

    public static final int WM_POWERBROADCAST = 536; // 0x218
    public static final int PBT_APMSUSPEND = 4;
    public static final int PBT_APMRESUMESUSPEND = 7;
    public static final int PBT_APMRESUMECRITICAL = 6;
    public static final int PBT_APMRESUMEAUTOMATIC = 18;

    int SetWindowLong(HWND hWnd, int nIndex, WindowProc dwNewLong);

    LRESULT CallWindowProc(LONG_PTR prevWndProc, HWND hWnd, int uMsg, WPARAM wParam, LPARAM lParam);
}
