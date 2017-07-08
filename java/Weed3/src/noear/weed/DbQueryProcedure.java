package noear.weed;

import noear.weed.cache.CacheState;
import noear.weed.ext.Act0;
import noear.weed.ext.Fun0;
import noear.weed.ext.Fun1;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


/**
 * Created by noear on 14-6-12.
 * 查询过程访问类（模拟存储过和）
 */
public class DbQueryProcedure extends DbAccess<DbQueryProcedure> {

    private Map<String,Variate> _paramS2 = new HashMap<>();

    public DbQueryProcedure(DbContext context){
        super(context);
    }

    /*延后初始化接口*/
    private Act0 _lazyload;
    private boolean _is_lazyload;
    protected void lazyload(Act0 action){
        _lazyload = action;
        _is_lazyload = false;
    }

    protected void tryLazyload() {
        if (_is_lazyload == false) {
            _is_lazyload = true;

            if (_lazyload != null) {
                _lazyload.run();
            }
        }
    }

    //---------

    protected DbQueryProcedure sql(String sqlCode) {
        this.commandText = sqlCode;
        this.paramS.clear();
        this._weedKey = null;

        if(_lazyload == null) { //如果是后续加载的话，不能清掉这些参数
            this._paramS2.clear();
        }

        return this;
    }

    private  DbQueryProcedure doSqlItem(String sqlCode){
        this.commandText = sqlCode;
        this.paramS.clear();
        this._weedKey = null;

        return this;
    }

    public DbQueryProcedure set(String param, Object value) {
        _paramS2.put(param,new Variate(param,value));
        return this;
    }

    public DbQueryProcedure set(String param, Fun0<Object> valueGetter) {
        _paramS2.put(param, new VariateEx(param, valueGetter));
        return this;
    }

    @Override
    public String getWeedKey() {
        return buildWeedKey(_paramS2.values());
    }

    @Override
    protected String getCommandID() {
        tryLazyload();

        return this.commandText;
    }

    @Override
    protected Command getCommand() throws SQLException{
        tryLazyload();

        Command cmd = new Command(this.context);

        cmd.key      = getCommandID();

        String sqlTxt = this.commandText;

        {
            Pattern pattern = Pattern.compile("@\\w+");
            Matcher m = pattern.matcher(sqlTxt);
            while (m.find()) {
                String key = m.group(0);
                if(WeedConfig.isDebug){
                    if(_paramS2.containsKey(key)==false){
                        throw new SQLException("Lack of parameter:"+key);
                    }
                }
                doSet(_paramS2.get(key));
            }

            for (String key : _paramS2.keySet()) {
                sqlTxt = sqlTxt.replace(key, "?");
            }
        }

        if(context.hasSchema()){
            sqlTxt.replace("$",context.getSchema());
        }

        cmd.paramS  = this.paramS;
        cmd.text    = sqlTxt;

        return cmd;
    }

    @Override
    public int execute() throws SQLException {
        tryLazyload();

        int num = 0;
        String[] sqlList = commandText.split(";"); //支持多段SQL执行
        for (String sql : sqlList) {
            if (sql.length() > 10) {
                doSqlItem(sql);

                num += super.execute();
            }
        }

        return num;
    }

    //=================================
    //
    //以下未测试
    //

    public <T extends IBinder> List<T> getListBySplit(T model , String splitParamName, Fun1<String,T> getKey) throws SQLException
    {
        //如果没有缓存,则直接返回执行结果
        //
        if (_cache == null ||_cache.cacheController == CacheState.NonUsing)
            return getList(model);


        //1.获取所有分拆后的WeedCode
        //
        List<ValueMapping> vmlist = new ArrayList<>(do_splitWeedCode(splitParamName));

        List<T> list = new ArrayList<T>(vmlist.size());
        StringBuilder sb = new StringBuilder();

        //2.根据WeedCode=>WeedKey获取已缓存的数据
        //
        for (ValueMapping vm : vmlist)
        {
            T temp = _cache.getOnly(vm.weedCode);

            if (temp != null)
            {
                vm.isCached = true;
                list.add(temp);
            }
            else
            {
                vm.isCached = false;
                sb.append(vm.value + ",");
            }
        }

        //3.获取未缓存的数据，并进行缓存
        //
        if (sb.length() > 0)
        {
            sb.delete(sb.length() - 1, 1);

            doGet(splitParamName).setValue(sb.toString());

            List<T> newList1 = getList(model);

            for (T ent : newList1)
            {
                String weedKey = do_getSubWeedCode(vmlist, splitParamName, getKey.run(ent));

                _cache.storeOnly(weedKey, ent);
            }

            list.addAll(newList1);
        }

        return list;
    }

    //-------

    private List<ValueMapping> do_splitWeedCode(String paramName)
    {
        List<ValueMapping> list = new ArrayList<>();

        String[] subKeyValue = doGet(paramName).getValue().toString().split(",");

        for (String value : subKeyValue)
            list.add(do_buildSubWeedCode(paramName, value));

        return list;
    }

    private String do_getSubWeedCode(List<ValueMapping> vmList, String splitParamName, String value)
    {
        for (ValueMapping vm : vmList)
        {
            if (vm.value.equals(value))
                return vm.weedCode;
        }

        return do_buildSubWeedCode(splitParamName, value).weedCode;
    }

    private ValueMapping do_buildSubWeedCode(String paramName, String value) {
        StringBuilder sb = new StringBuilder();

        sb.append(this.getCommandID() + ":");

        for (Variate item : _paramS2.values()) {
            if (item.getName() == paramName)
                sb.append("_" + value.trim());
            else {
                Object val = item.getValue();

                if (val == null)
                    sb.append("_null");
                else
                    sb.append("_" + val.toString());
            }
        }

        return new ValueMapping(value, sb.toString());
    }
}
