package cn.itcast.account.service.impl;

import cn.itcast.account.entity.AccountFreeze;
import cn.itcast.account.mapper.AccountFreezeMapper;
import cn.itcast.account.mapper.AccountMapper;
import cn.itcast.account.service.AccountTTCService;
import io.seata.core.context.RootContext;
import io.seata.rm.tcc.api.BusinessActionContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;

@Service
@Slf4j
public class AccountTTCServiceImpl implements AccountTTCService {

    @Resource
    private AccountMapper accountMapper;

    @Resource
    private AccountFreezeMapper accountFreezeMapper;

    @Transactional
    @Override
    public void deduct(String userId, int money) {

        // 0 获取事务的Id
        String xid = RootContext.getXID();
        // 判断freeze中是否有冻结记录，如果有的话，那一定就是cancel执行过了，拒绝义务
        AccountFreeze old = accountFreezeMapper.selectById(xid);
        if (old != null) {
            return;
        }

        // 1.尝试扣减可用余额
        accountMapper.deduct(userId, money);

        // 2.记录冻结金额和事务的状态
        AccountFreeze freeze = new AccountFreeze();
        freeze.setFreezeMoney(money);
        freeze.setUserId(userId);
        freeze.setState(AccountFreeze.State.TRY);
        freeze.setXid(xid);
        accountFreezeMapper.insert(freeze);

    }

    @Override
    public boolean confirm(BusinessActionContext context) {
        // 获取事务Id，根据事务删除冻结信息
        String xid = context.getXid();
        int count = accountFreezeMapper.deleteById(xid);
        return count == 1;
    }

    @Override
    public boolean cancel(BusinessActionContext context) {

        String xid = context.getXid();


        // 1 恢复可用金额
        AccountFreeze freeze = accountFreezeMapper.selectById(xid);
        String id = context.getActionContext("userId").toString();

        // 空回滚的判断，如果freeze为null，则证明try没有执行
        if (freeze == null) {
            freeze = new AccountFreeze();
            freeze.setFreezeMoney(0);
            freeze.setUserId(id);
            freeze.setState(AccountFreeze.State.CANCEL);
            freeze.setXid(xid);
            accountFreezeMapper.insert(freeze);
            return true;
        }

        // 幂等状态
        if (freeze.getState() == AccountFreeze.State.CANCEL) {

            // 已经处理过一次CANCEL了，无需重复处理
            return true;
        }

        String userId = freeze.getUserId();
        Integer freezeMoney = freeze.getFreezeMoney();

        // 2 将冻结金额清零，状态改为CANCEL
        freeze.setFreezeMoney(0);
        freeze.setState(AccountFreeze.State.CANCEL);

        int count = accountFreezeMapper.updateById(freeze);
        return count == 1;



    }
}
