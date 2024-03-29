/** Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *	 http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.razorvine.pickle.custom;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;

/**
 * Helper type that represents the unpickler working stack.
 * 
 * @author Irmen de Jong (irmen@razorvine.net)
 */
public class UnpickleStack implements Serializable {
	private static final long serialVersionUID = 6148106506441423350L;
	private ArrayList<Object> stack;
	protected Object MARKER;

	public UnpickleStack() {
		stack = new ArrayList<Object>();
		MARKER = new Object(); // any new unique object
	}

	public void add(Object o) {
		this.stack.add(o);
	}

	public void add_mark() {
		this.stack.add(this.MARKER);
	}

	public Object pop() {
		int size = this.stack.size();
		Object result = this.stack.get(size - 1);
		this.stack.remove(size - 1);
		return result;
	}

	public ArrayList<Object> pop_all_since_marker() {
		ArrayList<Object> result = new ArrayList<Object>();
		Object o = pop();
		while (o != this.MARKER) {
			result.add(o);
			o = pop();
		}
		result.trimToSize();
		Collections.reverse(result);
		return result;
	}

	public Object peek() {
		return this.stack.get(this.stack.size() - 1);
	}

	public void trim() {
		this.stack.trimToSize();
	}

	public int size() {
		return this.stack.size();
	}

	public void clear() {
		this.stack.clear();
		this.stack.trimToSize();
	}
}
