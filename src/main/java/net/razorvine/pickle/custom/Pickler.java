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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

import net.razorvine.pickle.Opcodes;
import net.razorvine.pickle.PickleException;
import net.razorvine.pickle.PickleUtils;
import net.razorvine.pickle.objects.Time;
import net.razorvine.pickle.objects.TimeDelta;
import scala.Product;

/**
 * Pickle an object graph into a Python-compatible pickle stream. For simplicity, the only supported pickle protocol at
 * this time is protocol 2. This class is NOT threadsafe! (Don't use the same pickler from different threads)
 *
 * See the README.txt for a table with the type mappings.
 * 
 * @author Irmen de Jong (irmen@razorvine.net)
 */
public class Pickler {

	public static int HIGHEST_PROTOCOL = 2;

	private static int MAX_RECURSE_DEPTH = 1000;
	private int recurse = 0; // recursion level
	private OutputStream out;
	private int PROTOCOL = 2;
	private static Map<Class<?>, IObjectPickler> customPicklers = new HashMap<Class<?>, IObjectPickler>();
	private boolean useMemo = true;
	private HashMap<Object, Integer> memo; // maps objects to memo index

	/**
	 * Create a Pickler.
	 */
	public Pickler() {
		this(true);
	}

	/**
	 * Create a Pickler. Specify if it is to use a memo table or not. The memo table is NOT reused across different
	 * calls. If you use a memo table, you can only pickle objects that are hashable.
	 */
	public Pickler(boolean useMemo) {
		this.useMemo = useMemo;
	}

	/**
	 * Close the pickler stream, discard any internal buffers.
	 */
	public void close() throws IOException {
		memo = null;
		out.flush();
		out.close();
	}

	/**
	 * Register additional object picklers for custom classes.
	 */
	public static void registerCustomPickler(Class<?> clazz, IObjectPickler pickler) {
		customPicklers.put(clazz, pickler);
	}

	/**
	 * Pickle a given object graph, returning the result as a byte array.
	 */
	public byte[] dumps(Object o) throws PickleException, IOException {
		ByteArrayOutputStream bo = new ByteArrayOutputStream();
		dump(o, bo);
		bo.flush();
		return bo.toByteArray();
	}

	/**
	 * Pickle a given object graph, writing the result to the output stream.
	 */
	public void dump(Object o, OutputStream stream) throws IOException, PickleException {
		out = stream;
		recurse = 0;
		if (useMemo)
			memo = new HashMap<Object, Integer>();
		out.write(Opcodes.PROTO);
		out.write(PROTOCOL);
		save(o);
		memo = null; // get rid of the memo table
		out.write(Opcodes.STOP);
		out.flush();
		if (recurse != 0) // sanity check
			throw new PickleException("recursive structure error, please report this problem");
	}

	/**
	 * Pickle a single object and write its pickle representation to the output stream. Normally this is used internally
	 * by the pickler, but you can also utilize it from within custom picklers. This is handy if as part of the custom
	 * pickler, you need to write a couple of normal objects such as strings or ints, that are already supported by the
	 * pickler. This method can be called recursively to output sub-objects.
	 */
	public void save(Object o) throws PickleException, IOException {
		recurse++;
		if (recurse > MAX_RECURSE_DEPTH)
			throw new java.lang.StackOverflowError("recursion too deep in Pickler.save (>" + MAX_RECURSE_DEPTH + ")");

		// null type?
		if (o == null) {
			out.write(Opcodes.NONE);
			recurse--;
			return;
		}

		// check the memo table, otherwise simply dispatch
		Class<?> t = o.getClass();
		if (lookupMemo(t, o) || dispatch(t, o)) {
			recurse--;
			return;
		}

		throw new PickleException("couldn't pickle object of type " + t);
	}

	/**
	 * Write the object to the memo table and output a memo write opcode Only works for hashable objects
	 */
	public void writeMemo(Object obj) throws IOException {
		if (!this.useMemo)
			return;
		if (!memo.containsKey(obj)) {
			int memo_index = memo.size();
			memo.put(obj, memo_index);
			if (memo_index <= 0xFF) {
				out.write(Opcodes.BINPUT);
				out.write((byte) memo_index);
			} else {
				out.write(Opcodes.LONG_BINPUT);
				byte[] index_bytes = PickleUtils.integer_to_bytes(memo_index);
				out.write(index_bytes, 0, 4);
			}
		}
	}

	/**
	 * Check the memo table and output a memo lookup if the object is found
	 */
	private boolean lookupMemo(Class<?> objectType, Object obj) throws IOException {
		if (!this.useMemo)
			return false;
		if (!objectType.isPrimitive()) {
			if (!memo.containsKey(obj))
				return false;
			int memo_index = memo.get(obj);
			if (memo_index <= 0xff) {
				out.write(Opcodes.BINGET);
				out.write((byte) memo_index);
			} else {
				out.write(Opcodes.LONG_BINGET);
				byte[] index_bytes = PickleUtils.integer_to_bytes(memo_index);
				out.write(index_bytes, 0, 4);
			}
			return true;
		}
		return false;
	}

	/**
	 * Process a single object to be pickled.
	 */
	private boolean dispatch(Class<?> t, Object o) throws IOException {
		// is it a primitive array?
		Class<?> componentType = t.getComponentType();
		if (componentType != null) {
			if (componentType.isPrimitive()) {
				put_arrayOfPrimitives(componentType, o);
			} else {
				put_arrayOfObjects((Object[]) o);
			}

			return true;
		}

		// first the primitive types
		if (o instanceof Boolean || t.equals(Boolean.TYPE)) {
			put_bool((Boolean) o);
			return true;
		}
		if (o instanceof Byte || t.equals(Byte.TYPE)) {
			put_long(((Byte) o).longValue());
			return true;
		}
		if (o instanceof Short || t.equals(Short.TYPE)) {
			put_long(((Short) o).longValue());
			return true;
		}
		if (o instanceof Integer || t.equals(Integer.TYPE)) {
			put_long(((Integer) o).longValue());
			return true;
		}
		if (o instanceof Long || t.equals(Long.TYPE)) {
			put_long(((Long) o).longValue());
			return true;
		}
		if (o instanceof Float || t.equals(Float.TYPE)) {
			put_float(((Float) o).doubleValue());
			return true;
		}
		if (o instanceof Double || t.equals(Double.TYPE)) {
			put_float(((Double) o).doubleValue());
			return true;
		}
		if (o instanceof Character || t.equals(Character.TYPE)) {
			put_string("" + o);
			return true;
		}

		// check registry
		Class<?> tt = t;
		while (tt != null) {
			IObjectPickler custompickler = customPicklers.get(tt);
			if (custompickler != null) {
				custompickler.pickle(o, this.out, this);
				writeMemo(o);
				return true;
			}

			tt = tt.getSuperclass();
		}

		// more complex types
		if (o instanceof String) {
			put_string((String) o);
			return true;
		}
		if (o instanceof BigInteger) {
			put_bigint((BigInteger) o);
			return true;
		}
		if (o instanceof BigDecimal) {
			put_decimal((BigDecimal) o);
			return true;
		}
		if (o instanceof Calendar) {
			put_calendar((Calendar) o);
			return true;
		}
		if (o instanceof Time) {
			put_time((Time) o);
			return true;
		}
		if (o instanceof TimeDelta) {
			put_timedelta((TimeDelta) o);
			return true;
		}
		if (o instanceof java.util.Date) {
			// a java Date contains a date+time so map this on Calendar
			// which will be pickled as a datetime.
			java.util.Date date = (java.util.Date) o;
			Calendar cal = GregorianCalendar.getInstance();
			cal.setTime(date);
			put_calendar(cal);
			return true;
		}
		if (o instanceof Enum) {
			put_string(o.toString());
			return true;
		}
		if (o instanceof Set<?>) {
			put_set((Set<?>) o);
			return true;
		}
		if (o instanceof Map<?, ?>) {
			put_map((Map<?, ?>) o);
			return true;
		}
		if (o instanceof scala.collection.Map<?, ?>) {
			put_map((scala.collection.Map<?, ?>) o);
			return true;
		}

		if (o instanceof Iterable<?>) {
			put_iterator(((Iterable<?>) o).iterator());
			return true;
		}
		if (o instanceof Iterator<?>) {
			put_iterator((Iterator<?>) o);
			return true;
		}
		if (o instanceof scala.collection.Iterator<?>) {
			put_iterator((scala.collection.Iterator<?>) o);
			return true;
		}
		if (o instanceof scala.Product) {
			put_iterator(((Product) o).productIterator());
			return true;
		}

		// javabean
		if (o instanceof java.io.Serializable) {
			put_javabean(o);
			return true;
		}

		return false;
	}

	void put_iterator(Iterator<?> iterator) throws IOException {
		out.write(Opcodes.EMPTY_LIST);
		writeMemo(iterator);
		out.write(Opcodes.MARK);
		while (iterator.hasNext()) {
			save(iterator.next());
		}
		out.write(Opcodes.APPENDS);
	}

	void put_iterator(scala.collection.Iterator<?> iterator) throws IOException {
		out.write(Opcodes.EMPTY_LIST);
		writeMemo(iterator);
		out.write(Opcodes.MARK);
		while (iterator.hasNext()) {
			save(iterator.next());
		}
		out.write(Opcodes.APPENDS);
	}

	void put_map(Map<?, ?> o) throws IOException {
		out.write(Opcodes.EMPTY_DICT);
		writeMemo(o);
		out.write(Opcodes.MARK);
		for (Object k : o.keySet()) {
			save(k);
			save(o.get(k));
		}
		out.write(Opcodes.SETITEMS);
	}

	@SuppressWarnings({ "rawtypes", "unchecked" })
	void put_map(scala.collection.Map o) throws IOException {
		out.write(Opcodes.EMPTY_DICT);
		writeMemo(o);
		out.write(Opcodes.MARK);

		scala.collection.Iterator keys = o.keysIterator();

		while (keys.hasNext()) {
			Object k = keys.next();
			save(k);
			save(o.apply(k));
		}

		out.write(Opcodes.SETITEMS);
	}

	void put_set(Set<?> o) throws IOException {
		out.write(Opcodes.GLOBAL);
		out.write("__builtin__\nset\n".getBytes());
		out.write(Opcodes.EMPTY_LIST);
		writeMemo(o);
		out.write(Opcodes.MARK);
		for (Object x : o) {
			save(x);
		}
		out.write(Opcodes.APPENDS);
		out.write(Opcodes.TUPLE1);
		out.write(Opcodes.REDUCE);
	}

	void put_calendar(Calendar cal) throws IOException {
		out.write(Opcodes.GLOBAL);
		out.write("datetime\ndatetime\n".getBytes());
		out.write(Opcodes.MARK);
		save(cal.get(Calendar.YEAR));
		save(cal.get(Calendar.MONTH) + 1); // months start at 0 in java
		save(cal.get(Calendar.DAY_OF_MONTH));
		save(cal.get(Calendar.HOUR_OF_DAY));
		save(cal.get(Calendar.MINUTE));
		save(cal.get(Calendar.SECOND));
		save(cal.get(Calendar.MILLISECOND) * 1000);
		out.write(Opcodes.TUPLE);
		out.write(Opcodes.REDUCE);
		writeMemo(cal);
	}

	void put_timedelta(TimeDelta delta) throws IOException {
		out.write(Opcodes.GLOBAL);
		out.write("datetime\ntimedelta\n".getBytes());
		save(delta.days);
		save(delta.seconds);
		save(delta.microseconds);
		out.write(Opcodes.TUPLE3);
		out.write(Opcodes.REDUCE);
		writeMemo(delta);
	}

	void put_time(Time time) throws IOException {
		out.write(Opcodes.GLOBAL);
		out.write("datetime\ntime\n".getBytes());
		out.write(Opcodes.MARK);
		save(time.hours);
		save(time.minutes);
		save(time.seconds);
		save(time.microseconds);
		out.write(Opcodes.TUPLE);
		out.write(Opcodes.REDUCE);
		writeMemo(time);
	}

	void put_arrayOfObjects(Object[] array) throws IOException {
		// 0 objects->EMPTYTUPLE
		// 1 object->TUPLE1
		// 2 objects->TUPLE2
		// 3 objects->TUPLE3
		// 4 or more->MARK+items+TUPLE
		if (array.length == 0) {
			out.write(Opcodes.EMPTY_TUPLE);
		} else if (array.length == 1) {
			if (array[0] == array)
				throw new PickleException("recursive array not supported, use list");
			save(array[0]);
			out.write(Opcodes.TUPLE1);
		} else if (array.length == 2) {
			if (array[0] == array || array[1] == array)
				throw new PickleException("recursive array not supported, use list");
			save(array[0]);
			save(array[1]);
			out.write(Opcodes.TUPLE2);
		} else if (array.length == 3) {
			if (array[0] == array || array[1] == array || array[2] == array)
				throw new PickleException("recursive array not supported, use list");
			save(array[0]);
			save(array[1]);
			save(array[2]);
			out.write(Opcodes.TUPLE3);
		} else {
			out.write(Opcodes.MARK);
			for (Object o : array) {
				if (o == array)
					throw new PickleException("recursive array not supported, use list");
				save(o);
			}
			out.write(Opcodes.TUPLE);
		}
		writeMemo(array); // tuples cannot contain self-references so it is fine to put this at the end
	}

	void put_arrayOfPrimitives(Class<?> t, Object array) throws IOException {

		if (t.equals(Boolean.TYPE)) {
			// a bool[] isn't written as an array but rather as a tuple
			boolean[] source = (boolean[]) array;
			Boolean[] boolarray = new Boolean[source.length];
			for (int i = 0; i < source.length; ++i) {
				boolarray[i] = source[i];
			}
			put_arrayOfObjects(boolarray);
			return;
		}
		if (t.equals(Character.TYPE)) {
			// a char[] isn't written as an array but rather as a unicode string
			String s = new String((char[]) array);
			put_string(s);
			return;
		}
		if (t.equals(Byte.TYPE)) {
			// a byte[] isn't written as an array but rather as a bytearray object
			out.write(Opcodes.GLOBAL);
			out.write("__builtin__\nbytearray\n".getBytes());
			String str = PickleUtils.rawStringFromBytes((byte[]) array);
			put_string(str);
			put_string("latin-1"); // this is what Python writes in the pickle
			out.write(Opcodes.TUPLE2);
			out.write(Opcodes.REDUCE);
			writeMemo(array);
			return;
		}

		out.write(Opcodes.GLOBAL);
		out.write("array\narray\n".getBytes());
		out.write(Opcodes.SHORT_BINSTRING); // array typecode follows
		out.write(1); // typecode is 1 char

		if (t.equals(Short.TYPE)) {
			out.write('h'); // signed short
			out.write(Opcodes.EMPTY_LIST);
			out.write(Opcodes.MARK);
			for (short s : (short[]) array) {
				save(s);
			}
		} else if (t.equals(Integer.TYPE)) {
			out.write('i'); // signed int
			out.write(Opcodes.EMPTY_LIST);
			out.write(Opcodes.MARK);
			for (int i : (int[]) array) {
				save(i);
			}
		} else if (t.equals(Long.TYPE)) {
			out.write('l'); // signed long
			out.write(Opcodes.EMPTY_LIST);
			out.write(Opcodes.MARK);
			for (long v : (long[]) array) {
				save(v);
			}
		} else if (t.equals(Float.TYPE)) {
			out.write('f'); // float
			out.write(Opcodes.EMPTY_LIST);
			out.write(Opcodes.MARK);
			for (float f : (float[]) array) {
				save(f);
			}
		} else if (t.equals(Double.TYPE)) {
			out.write('d'); // double
			out.write(Opcodes.EMPTY_LIST);
			out.write(Opcodes.MARK);
			for (double d : (double[]) array) {
				save(d);
			}
		}

		out.write(Opcodes.APPENDS);
		out.write(Opcodes.TUPLE2);
		out.write(Opcodes.REDUCE);

		writeMemo(array); // array of primitives can by definition never be recursive, so okay to put this at the end
	}

	void put_decimal(BigDecimal d) throws IOException {
		// "cdecimal\nDecimal\nU\n12345.6789\u0085R."
		out.write(Opcodes.GLOBAL);
		out.write("decimal\nDecimal\n".getBytes());
		put_string(d.toEngineeringString());
		out.write(Opcodes.TUPLE1);
		out.write(Opcodes.REDUCE);
		writeMemo(d);
	}

	void put_bigint(BigInteger i) throws IOException {
		byte[] b = PickleUtils.encode_long(i);
		if (b.length <= 0xff) {
			out.write(Opcodes.LONG1);
			out.write(b.length);
			out.write(b);
		} else {
			out.write(Opcodes.LONG4);
			out.write(PickleUtils.integer_to_bytes(b.length));
			out.write(b);
		}
	}

	void put_string(String string) throws IOException {
		byte[] encoded = string.getBytes("UTF-8");
		out.write(Opcodes.BINUNICODE);
		out.write(PickleUtils.integer_to_bytes(encoded.length));
		out.write(encoded);
		writeMemo(string);
	}

	void put_float(double d) throws IOException {
		out.write(Opcodes.BINFLOAT);
		out.write(PickleUtils.double_to_bytes(d));
	}

	void put_long(long v) throws IOException {
		// choose optimal representation
		// first check 1 and 2-byte unsigned ints:
		if (v >= 0) {
			if (v <= 0xff) {
				out.write(Opcodes.BININT1);
				out.write((int) v);
				return;
			}
			if (v <= 0xffff) {
				out.write(Opcodes.BININT2);
				out.write((int) v & 0xff);
				out.write((int) v >> 8);
				return;
			}
		}

		// 4-byte signed int?
		long high_bits = v >> 31; // shift sign extends
		if (high_bits == 0 || high_bits == -1) {
			// All high bits are copies of bit 2**31, so the value fits in a 4-byte signed int.
			out.write(Opcodes.BININT);
			out.write(PickleUtils.integer_to_bytes((int) v));
			return;
		}

		// int too big, store it as text
		out.write(Opcodes.INT);
		out.write(("" + v).getBytes());
		out.write('\n');
	}

	void put_bool(boolean b) throws IOException {
		if (b)
			out.write(Opcodes.NEWTRUE);
		else
			out.write(Opcodes.NEWFALSE);
	}

	void put_javabean(Object o) throws PickleException, IOException {
		Map<String, Object> map = new HashMap<String, Object>();
		try {
			// note: don't use the java.bean api, because that is not available on Android.
			for (Method m : o.getClass().getMethods()) {
				int modifiers = m.getModifiers();
				if ((modifiers & Modifier.PUBLIC) != 0 && (modifiers & Modifier.STATIC) == 0) {
					String methodname = m.getName();
					int prefixlen = 0;
					if (methodname.equals("getClass"))
						continue;
					if (methodname.startsWith("get"))
						prefixlen = 3;
					else if (methodname.startsWith("is"))
						prefixlen = 2;
					else
						continue;
					Object value = m.invoke(o);
					String name = methodname.substring(prefixlen);
					if (name.length() == 1) {
						name = name.toLowerCase();
					} else {
						if (!Character.isUpperCase(name.charAt(1))) {
							name = Character.toLowerCase(name.charAt(0)) + name.substring(1);
						}
					}
					map.put(name, value);
				}
			}
			map.put("__class__", o.getClass().getName());
			save(map);
		} catch (IllegalArgumentException e) {
			throw new PickleException("couldn't introspect javabean: " + e);
		} catch (IllegalAccessException e) {
			throw new PickleException("couldn't introspect javabean: " + e);
		} catch (InvocationTargetException e) {
			throw new PickleException("couldn't introspect javabean: " + e);
		}
	}
}
